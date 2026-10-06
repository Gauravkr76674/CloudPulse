package com.cloudops.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.cloudops.agent.aws.CloudProvider;
import com.cloudops.agent.aws.model.PermissionCheck;
import com.cloudops.agent.aws.model.ProviderActionResult;
import com.cloudops.agent.config.RemediationProperties;
import com.cloudops.agent.dto.ActionExecutionDto;
import com.cloudops.agent.dto.ApproveRecommendationRequest;
import com.cloudops.agent.entity.ActionExecution;
import com.cloudops.agent.entity.ActionStatus;
import com.cloudops.agent.entity.ActionType;
import com.cloudops.agent.entity.CloudResource;
import com.cloudops.agent.entity.Incident;
import com.cloudops.agent.entity.IncidentStatus;
import com.cloudops.agent.entity.Recommendation;
import com.cloudops.agent.entity.RecommendationStatus;
import com.cloudops.agent.entity.ResourceStatus;
import com.cloudops.agent.entity.ResourceType;
import com.cloudops.agent.exception.ActionNotAllowedException;
import com.cloudops.agent.exception.BadRequestException;
import com.cloudops.agent.repository.ActionExecutionRepository;
import com.cloudops.agent.repository.RecommendationRepository;
import com.cloudops.agent.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The safety envelope, guardrail by guardrail.
 *
 * Each test drives one refusal and asserts two things: the action row says BLOCKED with a
 * reason, and the provider was never touched. The recording handlers below stand in for the
 * real ones so "did anything reach AWS?" is a hard assertion rather than an inspection.
 *
 * Lenient stubbing is deliberate: the shared fixture stubs the whole happy path, and most
 * tests short-circuit part of it on purpose.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActionExecutionServiceTest {

    @Mock private ActionExecutionRepository actionRepository;
    @Mock private RecommendationRepository recommendationRepository;
    @Mock private RecommendationService recommendationService;
    @Mock private IncidentService incidentService;
    @Mock private NotificationService notificationService;
    @Mock private AuditService auditService;
    @Mock private CloudProvider cloudProvider;

    /** Records what was dispatched instead of calling a provider. */
    private static final class RecordingHandler implements ActionHandler {
        private final ActionType type;
        private final List<Target> calls = new ArrayList<>();
        private ProviderActionResult result = ProviderActionResult.ok("done", "req-1");

        private RecordingHandler(ActionType type) {
            this.type = type;
        }

        @Override
        public ActionType actionType() {
            return type;
        }

        @Override
        public ProviderActionResult execute(Target target) {
            calls.add(target);
            return result;
        }
    }

    private RemediationProperties properties;
    private ActionExecutionService service;
    private RecordingHandler rebootHandler;
    private List<ActionHandler> handlers;

    private CloudResource resource;
    private Incident incident;
    private Recommendation recommendation;

    @BeforeEach
    void setUp() {
        properties = new RemediationProperties();
        properties.setExecutionEnabled(true);
        properties.setRequireApproval(true);
        properties.setForceDryRun(false);
        properties.setMaxActionsPerResourcePerHour(3);
        // off by default so tests that do not care about IAM need no provider stubbing
        properties.setIamPreflightEnabled(false);

        // one handler per action type: the constructor refuses an incomplete table
        handlers = new ArrayList<>();
        for (ActionType type : ActionType.values()) {
            handlers.add(new RecordingHandler(type));
        }
        rebootHandler = (RecordingHandler) handlers.stream()
                .filter(h -> h.actionType() == ActionType.REBOOT_EC2_INSTANCE)
                .findFirst()
                .orElseThrow();

        resource = new CloudResource();
        resource.setId(7L);
        resource.setResourceId("i-0abc");
        resource.setName("demo-web-01");
        resource.setType(ResourceType.EC2_INSTANCE);
        resource.setStatus(ResourceStatus.RUNNING);

        incident = new Incident();
        incident.setId(42L);
        incident.setResource(resource);

        recommendation = new Recommendation();
        recommendation.setId(100L);
        recommendation.setIncident(incident);
        recommendation.setActionType(ActionType.REBOOT_EC2_INSTANCE);
        recommendation.setStatus(RecommendationStatus.PROPOSED);
        recommendation.setAiSuggestedAction("REBOOT_EC2_INSTANCE");

        when(recommendationService.get(100L)).thenReturn(recommendation);
        when(recommendationRepository.findIncidentIdById(100L)).thenReturn(Optional.of(42L));
        when(incidentService.get(42L)).thenReturn(incident);
        when(actionRepository.countByResourceAndCreatedAtAfterAndStatusIn(any(), any(), any()))
                .thenReturn(0L);

        AtomicLong ids = new AtomicLong(500);
        when(actionRepository.save(any(ActionExecution.class))).thenAnswer(invocation -> {
            ActionExecution row = invocation.getArgument(0);
            if (row.getId() == null) {
                row.setId(ids.incrementAndGet());
            }
            return row;
        });

        service = newService();
        authenticateAs("admin", "ADMIN");
    }

    private ActionExecutionService newService() {
        return new ActionExecutionService(
                actionRepository,
                recommendationRepository,
                recommendationService,
                incidentService,
                notificationService,
                auditService,
                new ActionCatalog(),
                cloudProvider,
                properties,
                new JsonUtil(new ObjectMapper()),
                handlers);
    }

    private void authenticateAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // the happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an approved whitelisted action runs exactly one handler and succeeds")
    void approvedActionExecutes() {
        ActionExecutionDto dto = service.approveAndExecute(100L, new ApproveRecommendationRequest(false, "go"));

        assertThat(dto.status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(dto.dryRun()).isFalse();
        assertThat(dto.approvedBy()).isEqualTo("admin");
        assertThat(rebootHandler.calls).hasSize(1);
        assertThat(rebootHandler.calls.get(0).providerResourceId()).isEqualTo("i-0abc");
        assertThat(rebootHandler.calls.get(0).actionType()).isEqualTo(ActionType.REBOOT_EC2_INSTANCE);

        verify(recommendationService).markApproved(100L, "go");
        verify(recommendationService).markExecuted(100L);
        verify(auditService).success(eq("ACTION_EXECUTED"), eq("ActionExecution"), any(), anyString());
        // a real mutation must NOT resolve the incident: only recovered metrics do that
        verify(incidentService).markStatus(42L, IncidentStatus.REMEDIATING);
        verify(incidentService, never()).markStatus(42L, IncidentStatus.OPEN);
    }

    @Test
    @DisplayName("no handler is ever selected by string - only by enum")
    void handlerTableIsCompleteAndUnambiguous() {
        // both are startup failures rather than runtime surprises
        List<ActionHandler> duplicated = new ArrayList<>(handlers);
        duplicated.add(new RecordingHandler(ActionType.REBOOT_EC2_INSTANCE));
        handlers = duplicated;
        assertThatThrownBy(this::newService)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Two handlers claim REBOOT_EC2_INSTANCE");

        handlers = List.of(new RecordingHandler(ActionType.NO_ACTION));
        assertThatThrownBy(this::newService)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No ActionHandler is registered for");
    }

    // ------------------------------------------------------------------
    // guardrails
    // ------------------------------------------------------------------

    @Test
    @DisplayName("dry run walks every guardrail and audits, but never calls the provider")
    void dryRunTouchesNothing() {
        ActionExecutionDto dto = service.approveAndExecute(100L, new ApproveRecommendationRequest(true, null));

        assertThat(dto.status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(dto.dryRun()).isTrue();
        assertThat(dto.resultMessage()).startsWith("DRY RUN:");
        assertThat(rebootHandler.calls).isEmpty();
        verifyNoInteractions(cloudProvider);

        // it is still a real, audited approval
        verify(recommendationService).markApproved(100L, null);
        verify(auditService).success(eq("ACTION_APPROVED"), eq("Recommendation"), eq(100L), anyString());
        // nothing changed in AWS, so the incident goes back to the queue rather than REMEDIATING
        verify(incidentService, never()).markStatus(42L, IncidentStatus.REMEDIATING);
        verify(incidentService).markStatus(42L, IncidentStatus.OPEN);
    }

    @Test
    @DisplayName("force-dry-run overrides a caller asking for a real execution")
    void forceDryRunWins() {
        properties.setForceDryRun(true);

        ActionExecutionDto dto = service.approveAndExecute(100L, new ApproveRecommendationRequest(false, null));

        assertThat(dto.dryRun()).isTrue();
        assertThat(rebootHandler.calls).isEmpty();
        verifyNoInteractions(cloudProvider);
    }

    @Test
    @DisplayName("the kill switch blocks execution instead of failing silently")
    void killSwitchBlocks() {
        properties.setExecutionEnabled(false);

        ActionExecutionDto dto = service.approveAndExecute(100L, new ApproveRecommendationRequest(false, null));

        assertThat(dto.status()).isEqualTo(ActionStatus.BLOCKED);
        assertThat(dto.blockedReason()).contains("execution-enabled=false");
        assertThat(rebootHandler.calls).isEmpty();
        verifyNoInteractions(cloudProvider);
        verify(notificationService).actionBlocked(any(ActionExecution.class));
        verify(auditService).denied(eq("ACTION_BLOCKED"), eq("Recommendation"), eq(100L), anyString());
        // still retryable once the switch is turned back on
        verify(recommendationService, never()).markApproved(anyLong(), anyString());
    }

    @Test
    @DisplayName("a non-ADMIN caller cannot execute an approval-required action")
    void nonAdminIsBlocked() {
        authenticateAs("operator", "OPERATOR");

        ActionExecutionDto dto = service.approveAndExecute(100L, new ApproveRecommendationRequest(false, null));

        assertThat(dto.status()).isEqualTo(ActionStatus.BLOCKED);
        assertThat(dto.blockedReason()).contains("requires ADMIN approval");
        assertThat(rebootHandler.calls).isEmpty();
    }

    @Test
    @DisplayName("the catalog is re-checked against the CURRENT resource state")
    void staleActionIsBlocked() {
        // the AI proposed a reboot while it was running; it has stopped since
        resource.setStatus(ResourceStatus.STOPPED);

        ActionExecutionDto dto = service.approveAndExecute(100L, new ApproveRecommendationRequest(false, null));

        assertThat(dto.status()).isEqualTo(ActionStatus.BLOCKED);
        assertThat(dto.blockedReason()).contains("needs a running instance");
        assertThat(rebootHandler.calls).isEmpty();
    }

    @Test
    @DisplayName("the hourly rate limit stops a reboot loop")
    void rateLimitBlocks() {
        when(actionRepository.countByResourceAndCreatedAtAfterAndStatusIn(any(), any(), any()))
                .thenReturn(3L);

        ActionExecutionDto dto = service.approveAndExecute(100L, new ApproveRecommendationRequest(false, null));

        assertThat(dto.status()).isEqualTo(ActionStatus.BLOCKED);
        assertThat(dto.blockedReason()).contains("Rate limit reached");
        assertThat(rebootHandler.calls).isEmpty();
    }

    @Test
    @DisplayName("an IAM denial blocks before the mutation is attempted")
    void iamDenialBlocks() {
        properties.setIamPreflightEnabled(true);
        when(cloudProvider.checkPermission("ec2:RebootInstances", "*"))
                .thenReturn(PermissionCheck.denied("ec2:RebootInstances", "implicitDeny"));

        ActionExecutionDto dto = service.approveAndExecute(100L, new ApproveRecommendationRequest(false, null));

        assertThat(dto.status()).isEqualTo(ActionStatus.BLOCKED);
        assertThat(dto.blockedReason()).contains("IAM pre-flight refused");
        assertThat(rebootHandler.calls).isEmpty();
    }

    @Test
    @DisplayName("an inconclusive IAM check does not block - AWS is the real enforcement point")
    void inconclusiveIamDoesNotBlock() {
        properties.setIamPreflightEnabled(true);
        when(cloudProvider.checkPermission("ec2:RebootInstances", "*"))
                .thenReturn(PermissionCheck.notEvaluated("cannot simulate an assumed role"));

        ActionExecutionDto dto = service.approveAndExecute(100L, new ApproveRecommendationRequest(false, null));

        assertThat(dto.status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(rebootHandler.calls).hasSize(1);
    }

    // ------------------------------------------------------------------
    // states that are caller errors rather than guardrail refusals
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a recommendation that is not PROPOSED cannot be approved twice")
    void alreadyApprovedIsRejected() {
        recommendation.setStatus(RecommendationStatus.EXECUTED);

        assertThatThrownBy(() -> service.approveAndExecute(100L, new ApproveRecommendationRequest(false, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cannot be approved");

        assertThat(rebootHandler.calls).isEmpty();
        verifyNoInteractions(cloudProvider);
    }

    @Test
    @DisplayName("a rejected-unsafe recommendation has no action type and can never execute")
    void unsafeRecommendationCannotExecute() {
        // exactly what RecommendationService stores when the model invents a command
        recommendation.setStatus(RecommendationStatus.PROPOSED);
        recommendation.setActionType(null);
        recommendation.setAiSuggestedAction("aws ec2 terminate-instances --instance-ids i-0abc");

        assertThatThrownBy(() -> service.approveAndExecute(100L, new ApproveRecommendationRequest(false, null)))
                .isInstanceOf(ActionNotAllowedException.class)
                .hasMessageContaining("no whitelisted action type");

        assertThat(rebootHandler.calls).isEmpty();
        verifyNoInteractions(cloudProvider);
        verify(actionRepository, never()).save(any(ActionExecution.class));
    }

    // ------------------------------------------------------------------
    // failure handling
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a provider failure is recorded and leaves the incident in the queue")
    void providerFailureIsRecorded() {
        rebootHandler.result = ProviderActionResult.failed("InsufficientInstanceCapacity");

        ActionExecutionDto dto = service.approveAndExecute(100L, new ApproveRecommendationRequest(false, null));

        assertThat(dto.status()).isEqualTo(ActionStatus.FAILED);
        assertThat(dto.errorMessage()).contains("InsufficientInstanceCapacity");
        verify(recommendationService).markFailed(eq(100L), anyString());
        verify(notificationService).actionFailed(any(ActionExecution.class));
        verify(incidentService).markStatus(42L, IncidentStatus.FAILED);
    }

    @Test
    @DisplayName("a handler that throws becomes a FAILED row, not a 500")
    void thrownHandlerErrorIsCaught() {
        List<ActionHandler> exploding = new ArrayList<>(handlers);
        exploding.removeIf(h -> h.actionType() == ActionType.REBOOT_EC2_INSTANCE);
        exploding.add(new ActionHandler() {
            @Override
            public ActionType actionType() {
                return ActionType.REBOOT_EC2_INSTANCE;
            }

            @Override
            public ProviderActionResult execute(Target target) {
                throw new IllegalStateException("connection reset");
            }
        });
        handlers = exploding;
        service = newService();

        ActionExecutionDto dto = service.approveAndExecute(100L, new ApproveRecommendationRequest(false, null));

        assertThat(dto.status()).isEqualTo(ActionStatus.FAILED);
        assertThat(dto.errorMessage()).contains("connection reset");
        verify(incidentService).markStatus(42L, IncidentStatus.FAILED);
    }
}
