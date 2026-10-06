package com.cloudops.agent.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cloudops.agent.ai.model.AiRecommendation;
import com.cloudops.agent.config.RemediationProperties;
import com.cloudops.agent.dto.RecommendationDto;
import com.cloudops.agent.entity.ActionType;
import com.cloudops.agent.entity.AiAnalysis;
import com.cloudops.agent.entity.Incident;
import com.cloudops.agent.entity.Recommendation;
import com.cloudops.agent.entity.RecommendationStatus;
import com.cloudops.agent.entity.RiskLevel;
import com.cloudops.agent.exception.BadRequestException;
import com.cloudops.agent.exception.NotFoundException;
import com.cloudops.agent.repository.AiAnalysisRepository;
import com.cloudops.agent.repository.IncidentRepository;
import com.cloudops.agent.repository.RecommendationRepository;
import com.cloudops.agent.security.CurrentUser;
import com.cloudops.agent.util.DtoMapper;
import com.cloudops.agent.util.Texts;

/**
 * Turns a model's suggestion into a stored decision — and this is where the safety boundary
 * actually lives.
 *
 * {@link #createFromAi} never trusts {@code aiRecommendation.action()}. It runs the string
 * through {@link ActionCatalog#resolve(String)}; anything that is not literally one of the
 * {@link ActionType} constants is stored with status {@code REJECTED_UNSAFE} together with
 * the original text, so there is a permanent record of what the model tried to do and proof
 * that it was never carried out. The same happens when the action is a real catalog entry
 * but does not apply to the resource in its current state.
 *
 * A rejected recommendation is evidence, not a task: nothing downstream will execute it,
 * because {@link ActionExecutionService} only accepts rows in {@code PROPOSED} state.
 *
 * This class deliberately does <b>not</b> execute anything. Creation, rejection and status
 * bookkeeping live here; execution lives behind the approval gate in
 * {@link ActionExecutionService}.
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private static final int DEFAULT_PRIORITY = 3;
    private static final int MIN_PRIORITY = 1;
    private static final int MAX_PRIORITY = 5;

    private final RecommendationRepository recommendationRepository;
    private final IncidentRepository incidentRepository;
    private final AiAnalysisRepository analysisRepository;
    private final ActionCatalog actionCatalog;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final RemediationProperties remediationProperties;

    public RecommendationService(RecommendationRepository recommendationRepository,
                               IncidentRepository incidentRepository,
                               AiAnalysisRepository analysisRepository,
                               ActionCatalog actionCatalog,
                               NotificationService notificationService,
                               AuditService auditService,
                               RemediationProperties remediationProperties) {
        this.recommendationRepository = recommendationRepository;
        this.incidentRepository = incidentRepository;
        this.analysisRepository = analysisRepository;
        this.actionCatalog = actionCatalog;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.remediationProperties = remediationProperties;
    }

    // ---------------------------------------------------------------------
    // creation from AI output
    // ---------------------------------------------------------------------

    /**
     * Stores one model suggestion, mapped onto the safe action catalog.
     *
     * @param incidentId  incident the suggestion belongs to
     * @param analysisId  the RECOMMENDATION-stage analysis row, may be null
     * @param suggestion  parsed model output; {@code suggestion.action()} is untrusted text
     * @return the stored recommendation, which may already be REJECTED_UNSAFE
     */
    @Transactional
    public Recommendation createFromAi(Long incidentId, Long analysisId, AiRecommendation suggestion) {
        Incident incident = requireIncident(incidentId);
        AiAnalysis analysis = analysisId == null
                ? null
                : analysisRepository.findById(analysisId).orElse(null);

        Recommendation recommendation = new Recommendation();
        recommendation.setIncident(incident);
        recommendation.setAnalysis(analysis);
        recommendation.setAiSuggestedAction(Texts.truncate(suggestion.action(), 200));
        recommendation.setTitle(Texts.truncate(
                Texts.defaultIfBlank(suggestion.title(), "AI recommendation"), 200));
        recommendation.setRationale(suggestion.rationale());
        recommendation.setExpectedImpact(suggestion.expectedImpact());
        recommendation.setConfidence(suggestion.confidence());
        recommendation.setPriority(clampPriority(suggestion.priority()));

        // ---- the whitelist check ----
        Optional<ActionType> resolved = actionCatalog.resolve(suggestion.action());
        if (resolved.isEmpty()) {
            recommendation.setActionType(null);
            recommendation.setRiskLevel(RiskLevel.parseOrDefault(suggestion.riskLevel(), RiskLevel.HIGH));
            recommendation.setStatus(RecommendationStatus.REJECTED_UNSAFE);
            recommendation.setRequiresApproval(false);
            recommendation.setRejectionReason(Texts.truncate(
                    "\"" + suggestion.action() + "\" is not in the safe action catalog. "
                            + "Stored as evidence; it was never executed.", 500));

            Recommendation saved = recommendationRepository.save(recommendation);
            log.warn("Rejected unsafe AI action \"{}\" on incident {}",
                    Texts.truncate(suggestion.action(), 120), incidentId);
            notificationService.unsafeRecommendationRejected(incident, suggestion.action());
            auditService.denied("AI_ACTION_REJECTED_UNSAFE", "Recommendation", saved.getId(),
                    "Model proposed an action outside the catalog: "
                            + Texts.truncate(suggestion.action(), 200));
            return saved;
        }

        ActionType actionType = resolved.get();
        recommendation.setActionType(actionType);
        recommendation.setRiskLevel(
                RiskLevel.parseOrDefault(suggestion.riskLevel(), actionType.getDefaultRisk()));

        // ---- the applicability check ----
        Optional<String> blocked = actionCatalog.rejectionReason(actionType, incident.getResource());
        if (blocked.isPresent()) {
            recommendation.setStatus(RecommendationStatus.REJECTED_UNSAFE);
            recommendation.setRequiresApproval(false);
            recommendation.setRejectionReason(Texts.truncate(blocked.get(), 500));

            Recommendation saved = recommendationRepository.save(recommendation);
            log.info("Recommendation {} ({}) does not apply to {}: {}", saved.getId(), actionType,
                    incident.getResource().getResourceId(), blocked.get());
            auditService.denied("AI_ACTION_NOT_APPLICABLE", "Recommendation", saved.getId(),
                    blocked.get());
            return saved;
        }

        boolean requiresApproval = remediationProperties.isRequireApproval()
                && actionType.isApprovalRequired();
        recommendation.setStatus(RecommendationStatus.PROPOSED);
        recommendation.setRequiresApproval(requiresApproval);

        Recommendation saved = recommendationRepository.save(recommendation);
        log.info("Recommendation {} on incident {}: {} (risk {}, approval {})", saved.getId(),
                incidentId, actionType, saved.getRiskLevel(), requiresApproval ? "required" : "not required");

        if (requiresApproval) {
            notificationService.approvalRequired(incident, saved);
        }
        auditService.success("RECOMMENDATION_CREATED", "Recommendation", saved.getId(),
                actionType + " for incident " + incidentId
                        + " (confidence " + Texts.number(saved.getConfidence()) + ")");
        return saved;
    }

    /** Records an alternative the model listed, so an approver can see the options considered. */
    @Transactional
    public void recordAlternatives(Long incidentId, Long analysisId, List<String> alternatives) {
        if (alternatives == null || alternatives.isEmpty()) {
            return;
        }
        Incident incident = requireIncident(incidentId);
        AiAnalysis analysis = analysisId == null
                ? null
                : analysisRepository.findById(analysisId).orElse(null);

        int priority = MAX_PRIORITY;
        for (String alternative : alternatives) {
            Optional<ActionType> resolved = actionCatalog.resolve(alternative);
            if (resolved.isEmpty()
                    || actionCatalog.rejectionReason(resolved.get(), incident.getResource()).isPresent()) {
                // Unusable alternatives are simply not offered; the primary path already
                // records anything the model got wrong
                continue;
            }
            Recommendation recommendation = new Recommendation();
            recommendation.setIncident(incident);
            recommendation.setAnalysis(analysis);
            recommendation.setActionType(resolved.get());
            recommendation.setAiSuggestedAction(Texts.truncate(alternative, 200));
            recommendation.setTitle(Texts.truncate(
                    "Alternative: " + resolved.get().getDisplayName(), 200));
            recommendation.setRationale("Listed by the model as an alternative to its primary recommendation.");
            recommendation.setRiskLevel(resolved.get().getDefaultRisk());
            recommendation.setPriority(priority);
            recommendation.setStatus(RecommendationStatus.PROPOSED);
            recommendation.setRequiresApproval(remediationProperties.isRequireApproval()
                    && resolved.get().isApprovalRequired());
            recommendationRepository.save(recommendation);
        }
    }

    // ---------------------------------------------------------------------
    // human decisions and bookkeeping
    // ---------------------------------------------------------------------

    @Transactional
    public Recommendation reject(Long recommendationId, String note) {
        Recommendation recommendation = require(recommendationId);
        if (!recommendation.isActionable()) {
            throw new BadRequestException("Recommendation " + recommendationId + " is "
                    + recommendation.getStatus() + " and can no longer be rejected.");
        }
        recommendation.setStatus(RecommendationStatus.REJECTED);
        recommendation.setDecidedBy(CurrentUser.username());
        recommendation.setDecidedAt(Instant.now());
        recommendation.setDecisionNote(Texts.truncate(note, 500));
        recommendation.setRejectionReason(Texts.truncate(
                Texts.defaultIfBlank(note, "Rejected by operator."), 500));

        Recommendation saved = recommendationRepository.save(recommendation);
        auditService.success("RECOMMENDATION_REJECTED", "Recommendation", recommendationId,
                Texts.defaultIfBlank(note, "no note"));
        return saved;
    }

    /** Called by the executor once an approval has passed every guardrail. */
    @Transactional
    public Recommendation markApproved(Long recommendationId, String note) {
        Recommendation recommendation = require(recommendationId);
        recommendation.setStatus(RecommendationStatus.APPROVED);
        recommendation.setDecidedBy(CurrentUser.username());
        recommendation.setDecidedAt(Instant.now());
        recommendation.setDecisionNote(Texts.truncate(note, 500));
        return recommendationRepository.save(recommendation);
    }

    @Transactional
    public void markExecuted(Long recommendationId) {
        recommendationRepository.findById(recommendationId).ifPresent(recommendation -> {
            recommendation.setStatus(RecommendationStatus.EXECUTED);
            recommendationRepository.save(recommendation);
        });
    }

    @Transactional
    public void markFailed(Long recommendationId, String error) {
        recommendationRepository.findById(recommendationId).ifPresent(recommendation -> {
            recommendation.setStatus(RecommendationStatus.FAILED);
            recommendation.setRejectionReason(Texts.truncate(error, 500));
            recommendationRepository.save(recommendation);
        });
    }

    // ---------------------------------------------------------------------
    // reads
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Recommendation get(Long recommendationId) {
        return require(recommendationId);
    }

    @Transactional(readOnly = true)
    public List<RecommendationDto> listForIncident(Long incidentId) {
        Incident incident = requireIncident(incidentId);
        return DtoMapper.toRecommendationDtos(
                recommendationRepository.findByIncidentOrderByPriorityAscCreatedAtAsc(incident));
    }

    /** The approval queue: everything still waiting on a human. */
    @Transactional(readOnly = true)
    public List<RecommendationDto> pending() {
        return DtoMapper.toRecommendationDtos(
                recommendationRepository.findByStatusOrderByPriorityAscCreatedAtDesc(
                        RecommendationStatus.PROPOSED));
    }

    /** Rejected-by-machine rows, kept as the audit trail of what the AI was refused. */
    @Transactional(readOnly = true)
    public List<RecommendationDto> blockedByGuardrails() {
        return DtoMapper.toRecommendationDtos(
                recommendationRepository.findByStatusInOrderByCreatedAtDesc(
                        List.of(RecommendationStatus.REJECTED_UNSAFE)));
    }

    private int clampPriority(Integer priority) {
        if (priority == null) {
            return DEFAULT_PRIORITY;
        }
        return Math.min(MAX_PRIORITY, Math.max(MIN_PRIORITY, priority));
    }

    private Recommendation require(Long recommendationId) {
        if (recommendationId == null) {
            // Spring Data would throw InvalidDataAccessApiUsageException here, which reads
            // like a server fault. A missing identifier is a bad request.
            throw new BadRequestException("A recommendation id must be provided");
        }
        return recommendationRepository.findById(recommendationId)
                .orElseThrow(() -> NotFoundException.of("Recommendation", recommendationId));
    }

    private Incident requireIncident(Long incidentId) {
        if (incidentId == null) {
            throw new BadRequestException("An incident id must be provided");
        }
        return incidentRepository.findById(incidentId)
                .orElseThrow(() -> NotFoundException.of("Incident", incidentId));
    }
}
