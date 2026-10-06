package com.cloudops.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cloudops.agent.ai.model.AiRecommendation;
import com.cloudops.agent.config.RemediationProperties;
import com.cloudops.agent.entity.ActionType;
import com.cloudops.agent.entity.CloudResource;
import com.cloudops.agent.entity.Incident;
import com.cloudops.agent.entity.Recommendation;
import com.cloudops.agent.entity.RecommendationStatus;
import com.cloudops.agent.entity.ResourceStatus;
import com.cloudops.agent.entity.ResourceType;
import com.cloudops.agent.entity.RiskLevel;
import com.cloudops.agent.repository.AiAnalysisRepository;
import com.cloudops.agent.repository.IncidentRepository;
import com.cloudops.agent.repository.RecommendationRepository;

/**
 * Where untrusted model output meets the database.
 *
 * The behaviour being pinned: an action the model invented is stored as evidence with status
 * REJECTED_UNSAFE and a null actionType, so nothing downstream can execute it — and an action
 * that is in the catalog but does not fit the resource's current state is refused too.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock private RecommendationRepository recommendationRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private AiAnalysisRepository analysisRepository;
    @Mock private NotificationService notificationService;
    @Mock private AuditService auditService;

    private RecommendationService service;
    private Incident incident;
    private CloudResource resource;

    @BeforeEach
    void setUp() {
        RemediationProperties remediationProperties = new RemediationProperties();
        remediationProperties.setRequireApproval(true);

        service = new RecommendationService(
                recommendationRepository,
                incidentRepository,
                analysisRepository,
                new ActionCatalog(),
                notificationService,
                auditService,
                remediationProperties);

        resource = new CloudResource();
        resource.setId(7L);
        resource.setResourceId("i-0abc");
        resource.setName("demo-web-01");
        resource.setType(ResourceType.EC2_INSTANCE);
        resource.setStatus(ResourceStatus.RUNNING);

        incident = new Incident();
        incident.setId(42L);
        incident.setResource(resource);
        incident.setTitle("High CPU on demo-web-01");

        when(incidentRepository.findById(42L)).thenReturn(Optional.of(incident));
        when(recommendationRepository.save(any(Recommendation.class)))
                .thenAnswer(invocation -> {
                    Recommendation saved = invocation.getArgument(0);
                    if (saved.getId() == null) {
                        saved.setId(100L);
                    }
                    return saved;
                });
    }

    private AiRecommendation suggestion(String action) {
        return new AiRecommendation(action, "Restart the instance", "CPU has been pinned at 97%",
                "Should clear the runaway process", "MEDIUM", 0.82, 1, List.of());
    }

    @Test
    @DisplayName("a whitelisted action becomes a PROPOSED recommendation awaiting approval")
    void whitelistedActionIsProposed() {
        Recommendation stored = service.createFromAi(42L, null, suggestion("REBOOT_EC2_INSTANCE"));

        assertThat(stored.getActionType()).isEqualTo(ActionType.REBOOT_EC2_INSTANCE);
        assertThat(stored.getStatus()).isEqualTo(RecommendationStatus.PROPOSED);
        assertThat(stored.isRequiresApproval()).isTrue();
        assertThat(stored.isActionable()).isTrue();
        verify(notificationService).approvalRequired(eq(incident), any(Recommendation.class));
    }

    @Test
    @DisplayName("an AWS CLI command from the model is stored as evidence, never as an action")
    void arbitraryCommandIsRejectedAsUnsafe() {
        String injected = "aws ec2 terminate-instances --instance-ids i-0abc";

        Recommendation stored = service.createFromAi(42L, null, suggestion(injected));

        assertThat(stored.getStatus()).isEqualTo(RecommendationStatus.REJECTED_UNSAFE);
        assertThat(stored.getActionType()).isNull();
        assertThat(stored.isRequiresApproval()).isFalse();
        assertThat(stored.isActionable()).isFalse();
        // the original text is kept so a reviewer can see exactly what was proposed
        assertThat(stored.getAiSuggestedAction()).isEqualTo(injected);
        assertThat(stored.getRejectionReason()).contains("not in the safe action catalog");

        verify(notificationService).unsafeRecommendationRejected(incident, injected);
        verify(auditService).denied(eq("AI_ACTION_REJECTED_UNSAFE"), eq("Recommendation"),
                any(), anyString());
        verify(notificationService, never()).approvalRequired(any(), any());
    }

    @Test
    @DisplayName("an unknown action defaults to HIGH risk rather than looking harmless")
    void unknownActionIsTreatedAsHighRisk() {
        Recommendation stored = service.createFromAi(42L, null,
                new AiRecommendation("DO_SOMETHING_CLEVER", "t", "r", "i", null, 0.5, 1, List.of()));

        assertThat(stored.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(stored.getStatus()).isEqualTo(RecommendationStatus.REJECTED_UNSAFE);
    }

    @Test
    @DisplayName("a catalog action that does not fit the resource state is refused")
    void inapplicableActionIsRejected() {
        resource.setStatus(ResourceStatus.STOPPED);

        Recommendation stored = service.createFromAi(42L, null, suggestion("REBOOT_EC2_INSTANCE"));

        assertThat(stored.getStatus()).isEqualTo(RecommendationStatus.REJECTED_UNSAFE);
        assertThat(stored.getRejectionReason()).contains("needs a running instance");
        verify(auditService).denied(eq("AI_ACTION_NOT_APPLICABLE"), eq("Recommendation"),
                any(), anyString());
    }

    @Test
    @DisplayName("an advisory action needs no approval and is still actionable")
    void advisoryActionNeedsNoApproval() {
        Recommendation stored = service.createFromAi(42L, null, suggestion("NOTIFY_ONCALL"));

        assertThat(stored.getActionType()).isEqualTo(ActionType.NOTIFY_ONCALL);
        assertThat(stored.getStatus()).isEqualTo(RecommendationStatus.PROPOSED);
        assertThat(stored.isRequiresApproval()).isFalse();
    }

    @Test
    @DisplayName("priority from the model is clamped into 1..5")
    void priorityIsClamped() {
        Recommendation high = service.createFromAi(42L, null,
                new AiRecommendation("NO_ACTION", "t", "r", "i", "LOW", 0.4, 99, List.of()));
        assertThat(high.getPriority()).isEqualTo(5);

        Recommendation low = service.createFromAi(42L, null,
                new AiRecommendation("NO_ACTION", "t", "r", "i", "LOW", 0.4, -3, List.of()));
        assertThat(low.getPriority()).isEqualTo(1);
    }
}
