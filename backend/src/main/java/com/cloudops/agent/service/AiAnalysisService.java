package com.cloudops.agent.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cloudops.agent.ai.AiJsonParser;
import com.cloudops.agent.ai.AiResponse;
import com.cloudops.agent.ai.AiService;
import com.cloudops.agent.ai.model.AiDiagnosis;
import com.cloudops.agent.ai.model.AiRecommendation;
import com.cloudops.agent.ai.model.AiStageOutcome;
import com.cloudops.agent.ai.model.AiTriage;
import com.cloudops.agent.ai.model.IncidentContext;
import com.cloudops.agent.dto.AiAnalysisDto;
import com.cloudops.agent.entity.AiAnalysis;
import com.cloudops.agent.entity.AiStage;
import com.cloudops.agent.entity.Incident;
import com.cloudops.agent.entity.IncidentStatus;
import com.cloudops.agent.entity.Recommendation;
import com.cloudops.agent.entity.RecommendationStatus;
import com.cloudops.agent.exception.NotFoundException;
import com.cloudops.agent.repository.AiAnalysisRepository;
import com.cloudops.agent.repository.IncidentRepository;
import com.cloudops.agent.util.DtoMapper;
import com.cloudops.agent.util.Texts;

/**
 * Runs the multi-stage AI pipeline over one incident and writes down everything it produced.
 *
 * <pre>
 *   DETECTION  (is this real, how bad)      -> AiAnalysis row
 *   DIAGNOSIS  (what is probably wrong)     -> AiAnalysis row, may raise severity
 *   RECOMMENDATION (what should we do)      -> AiAnalysis row + Recommendation rows
 * </pre>
 *
 * Each stage is routed to its own configured model by {@link AiService}, so the "multi-model"
 * design is a routing table rather than four hard-coded clients; one model with four
 * specialised prompts is a valid configuration of the same code.
 *
 * Two rules this class follows without exception:
 *
 * <ol>
 *   <li><b>Every call is recorded</b>, including failures — an AiAnalysis row with
 *       {@code successful = false} and the error message. An incident that the AI could not
 *       analyse must look different from one it chose not to act on.</li>
 *   <li><b>The AI never executes anything.</b> The furthest this pipeline goes is creating a
 *       PROPOSED recommendation and moving the incident to AWAITING_APPROVAL. Execution
 *       needs a human through {@link ActionExecutionService}.</li>
 * </ol>
 *
 * Writes go through repositories and other services rather than one big transaction, because
 * the model calls in between take seconds and must not hold a database transaction open.
 */
@Service
public class AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);

    private final AiService aiService;
    private final IncidentService incidentService;
    private final RecommendationService recommendationService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final AiAnalysisRepository analysisRepository;
    private final IncidentRepository incidentRepository;

    public AiAnalysisService(AiService aiService,
                            IncidentService incidentService,
                            RecommendationService recommendationService,
                            NotificationService notificationService,
                            AuditService auditService,
                            AiAnalysisRepository analysisRepository,
                            IncidentRepository incidentRepository) {
        this.aiService = aiService;
        this.incidentService = incidentService;
        this.recommendationService = recommendationService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.analysisRepository = analysisRepository;
        this.incidentRepository = incidentRepository;
    }

    public boolean autoAnalyzeEnabled() {
        return aiService.autoAnalyzeEnabled() && aiService.available();
    }

    /**
     * Fire-and-forget analysis, used by the metric poller so a slow model never delays the
     * next polling cycle. Runs on the {@code aiTaskExecutor} pool.
     */
    @Async("aiTaskExecutor")
    public void analyzeAsync(Long incidentId) {
        try {
            analyze(incidentId);
        } catch (RuntimeException ex) {
            // Nobody is waiting on this thread, so an escaping exception would vanish
            log.error("Background analysis of incident {} failed: {}", incidentId,
                    Texts.redactSecrets(String.valueOf(ex.getMessage())), ex);
        }
    }

    /**
     * The full pipeline, synchronously. Safe to call twice: it appends a new set of analysis
     * rows rather than overwriting, which is what you want when an operator asks for a
     * re-analysis after the situation has changed.
     */
    public List<AiAnalysisDto> analyze(Long incidentId) {
        List<AiAnalysisDto> produced = new ArrayList<>();
        IncidentContext context = incidentService.buildAiContext(incidentId);

        incidentService.markStatus(incidentId, IncidentStatus.ANALYZING);
        log.info("Analysing incident {} with provider {} ({})", incidentId,
                aiService.providerName(), aiService.routingTable());

        // ---------- stage 1: detection / triage ----------
        AiStageOutcome<AiTriage> triage = aiService.triage(context);
        AiAnalysis triageRow = recordTriage(incidentId, triage);
        produced.add(DtoMapper.toAnalysisDto(triageRow));

        if (!triage.successful()) {
            abort(incidentId, AiStage.DETECTION, triage.failureReason());
            return produced;
        }
        if (!triage.payload().genuineAnomaly()) {
            // Recorded, but the alarm is NOT silenced: a model's opinion does not close an
            // incident. It stays open, now with an explanation attached for the operator.
            log.info("AI judged incident {} a probable false positive: {}", incidentId,
                    triage.payload().signalAssessment());
        }
        incidentService.applyAiSeverity(incidentId, triage.payload().suggestedSeverity());

// Refresh context after detection updates the incident
        context = incidentService.buildAiContext(incidentId);

// ---------- stage 2: diagnosis ----------
        AiStageOutcome<AiDiagnosis> diagnosis = aiService.diagnose(context);
        AiAnalysis diagnosisRow = recordDiagnosis(incidentId, diagnosis);
        produced.add(DtoMapper.toAnalysisDto(diagnosisRow));

        if (!diagnosis.successful()) {
            abort(incidentId, AiStage.DIAGNOSIS, diagnosis.failureReason());
            return produced;
        }
        incidentService.applyAiSeverity(incidentId, diagnosis.payload().suggestedSeverity());

// Refresh context after diagnosis updates the incident
        context = incidentService.buildAiContext(incidentId);

// ---------- stage 3: recommendation ----------
        AiStageOutcome<AiRecommendation> recommendation =
                aiService.recommend(context, diagnosis.payload());
        AiAnalysis recommendationRow = recordRecommendation(incidentId, recommendation);
        produced.add(DtoMapper.toAnalysisDto(recommendationRow));

        if (!recommendation.successful()) {
            abort(incidentId, AiStage.RECOMMENDATION, recommendation.failureReason());
            return produced;
        }

        Recommendation stored = recommendationService.createFromAi(
                incidentId, recommendationRow.getId(), recommendation.payload());
        recommendationService.recordAlternatives(incidentId, recommendationRow.getId(),
                recommendation.payload().alternativeActions());

        Incident incident = requireIncident(incidentId);
        notificationService.analysisReady(incident, diagnosis.payload().summary());
        auditService.success("AI_ANALYSIS_COMPLETED", "Incident", incidentId,
                String.format("provider=%s stages=3 rootCause=%s recommendation=%s status=%s",
                        aiService.providerName(),
                        Texts.truncate(diagnosis.payload().rootCause(), 120),
                        stored.getActionType(), stored.getStatus()));

        // Only a genuinely usable recommendation moves the incident to the approval queue
        if (stored.getStatus() == RecommendationStatus.PROPOSED && stored.isRequiresApproval()) {
            incidentService.markStatus(incidentId, IncidentStatus.AWAITING_APPROVAL);
        } else {
            incidentService.markStatus(incidentId, IncidentStatus.OPEN);
        }
        return produced;
    }

    /**
     * Post-remediation check: did the action actually fix it? Called after an action succeeds.
     * Only auto-resolves when the model says recovered AND safe to close AND the metric data
     * agrees — the incident's own recovery rule still has to pass in
     * {@link IncidentService#checkRecovery}.
     */
    public AiAnalysisDto verifyRecovery(Long incidentId) {
        IncidentContext context = incidentService.buildAiContext(incidentId);
        AiStageOutcome<AiJsonParser.Verification> verification = aiService.verify(context);

        AiAnalysis row = newRow(incidentId, AiStage.VERIFICATION, verification.raw());
        if (verification.successful()) {
            AiJsonParser.Verification payload = verification.payload();
            row.setSummary(Texts.truncate(payload.summary(), 2000));
            row.setReasoning(payload.reasoning());
            row.setConfidence(payload.confidence());
            row.setSuccessful(true);
        } else {
            row.setSuccessful(false);
            row.setErrorMessage(Texts.truncate(verification.failureReason(), 1000));
        }
        AiAnalysis saved = analysisRepository.save(row);

        if (verification.successful()) {
            AiJsonParser.Verification payload = verification.payload();
            log.info("Verification of incident {}: recovered={} safeToClose={}", incidentId,
                    payload.recovered(), payload.safeToClose());
            if (payload.recovered() && payload.safeToClose()) {
                // The metric-based rule is the authority; this only asks it to re-check now
                Incident incident = requireIncident(incidentId);
                int resolved = incidentService.checkRecovery(incident.getResource(),
                        incident.getMetricName(), statisticOf(incident));
                if (resolved == 0) {
                    log.info("AI reports incident {} recovered, but the metric rule does not "
                            + "agree yet; leaving it open", incidentId);
                }
            }
        }
        return DtoMapper.toAnalysisDto(saved);
    }

    @Transactional(readOnly = true)
    public List<AiAnalysisDto> listForIncident(Long incidentId) {
        Incident incident = requireIncident(incidentId);
        return DtoMapper.toAnalysisDtos(analysisRepository.findByIncidentOrderByCreatedAtAsc(incident));
    }

    // ---------------------------------------------------------------------
    // persistence of each stage
    // ---------------------------------------------------------------------

    private AiAnalysis recordTriage(Long incidentId, AiStageOutcome<AiTriage> outcome) {
        AiAnalysis row = newRow(incidentId, AiStage.DETECTION, outcome.raw());
        if (outcome.successful()) {
            AiTriage payload = outcome.payload();
            row.setSummary(Texts.truncate(payload.summary(), 2000));
            row.setReasoning(payload.signalAssessment());
            row.setConfidence(payload.confidence());
            row.setSuggestedSeverity(payload.suggestedSeverity());
            row.setSuccessful(true);
        } else {
            row.setSuccessful(false);
            row.setErrorMessage(Texts.truncate(outcome.failureReason(), 1000));
        }
        return analysisRepository.save(row);
    }

    private AiAnalysis recordDiagnosis(Long incidentId, AiStageOutcome<AiDiagnosis> outcome) {
        AiAnalysis row = newRow(incidentId, AiStage.DIAGNOSIS, outcome.raw());
        if (outcome.successful()) {
            AiDiagnosis payload = outcome.payload();
            row.setSummary(Texts.truncate(payload.summary(), 2000));
            row.setRootCause(Texts.truncate(payload.rootCause(), 1000));
            row.setReasoning(payload.reasoning());
            row.setConfidence(payload.confidence());
            row.setSuggestedSeverity(payload.suggestedSeverity());
            row.setSuccessful(true);
        } else {
            row.setSuccessful(false);
            row.setErrorMessage(Texts.truncate(outcome.failureReason(), 1000));
        }
        return analysisRepository.save(row);
    }

    private AiAnalysis recordRecommendation(Long incidentId, AiStageOutcome<AiRecommendation> outcome) {
        AiAnalysis row = newRow(incidentId, AiStage.RECOMMENDATION, outcome.raw());
        if (outcome.successful()) {
            AiRecommendation payload = outcome.payload();
            row.setSummary(Texts.truncate(payload.title(), 2000));
            row.setReasoning(payload.rationale());
            row.setConfidence(payload.confidence());
            row.setSuccessful(true);
        } else {
            row.setSuccessful(false);
            row.setErrorMessage(Texts.truncate(outcome.failureReason(), 1000));
        }
        return analysisRepository.save(row);
    }

    /**
     * A blank analysis row pre-filled with everything we know about the call itself. The raw
     * reply is stored truncated for debugging; {@link Texts#redactSecrets} runs over it first
     * so an echoed key can never reach the database.
     */
    private AiAnalysis newRow(Long incidentId, AiStage stage, AiResponse response) {
        AiAnalysis row = new AiAnalysis();
        row.setIncident(requireIncident(incidentId));
        row.setStage(stage);
        if (response != null) {
            row.setProvider(response.provider());
            row.setModelName(response.model());
            row.setLatencyMs(response.latencyMs());
            row.setPromptTokens(response.promptTokens());
            row.setCompletionTokens(response.completionTokens());
            row.setRawResponse(Texts.truncate(Texts.redactSecrets(response.content()), 8000));
        } else {
            row.setProvider(aiService.providerName());
        }
        return row;
    }

    /** Records the failure on the incident itself so the dashboard shows why nothing happened. */
    private void abort(Long incidentId, AiStage stage, String reason) {
        log.warn("AI pipeline stopped at {} for incident {}: {}", stage, incidentId, reason);
        auditService.failure("AI_ANALYSIS_FAILED", "Incident", incidentId,
                stage + ": " + Texts.truncate(reason, 400));
        // Back to OPEN: an incident nobody analysed still needs a human
        incidentService.markStatus(incidentId, IncidentStatus.OPEN);
    }

    /** Matches the statistic the poller uses, so recovery counting looks at the same series. */
    private String statisticOf(Incident incident) {
        String metricName = incident.getMetricName();
        return metricName != null && metricName.startsWith("StatusCheckFailed")
                ? "Maximum"
                : "Average";
    }

    private Incident requireIncident(Long incidentId) {
        return incidentRepository.findById(incidentId)
                .orElseThrow(() -> NotFoundException.of("Incident", incidentId));
    }
}
