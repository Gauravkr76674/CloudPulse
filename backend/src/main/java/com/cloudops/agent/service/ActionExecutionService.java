package com.cloudops.agent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cloudops.agent.aws.CloudProvider;
import com.cloudops.agent.aws.model.PermissionCheck;
import com.cloudops.agent.aws.model.ProviderActionResult;
import com.cloudops.agent.config.RemediationProperties;
import com.cloudops.agent.dto.ActionCatalogEntryDto;
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
import com.cloudops.agent.exception.ActionNotAllowedException;
import com.cloudops.agent.exception.BadRequestException;
import com.cloudops.agent.exception.NotFoundException;
import com.cloudops.agent.repository.ActionExecutionRepository;
import com.cloudops.agent.repository.RecommendationRepository;
import com.cloudops.agent.security.CurrentUser;
import com.cloudops.agent.util.DtoMapper;
import com.cloudops.agent.util.JsonUtil;
import com.cloudops.agent.util.Texts;

/**
 * The only place in the application that changes anything in AWS.
 *
 * Every approval walks the same fixed path, and each step can only narrow what happens:
 *
 * <pre>
 *   1. state guard      recommendation must still be PROPOSED
 *   2. whitelist        actionType must be a resolved ActionType (never free text)
 *   3. kill switch      cloudops.remediation.execution-enabled
 *   4. authority        an ADMIN must be the caller for approval-required actions
 *   5. catalog re-check ActionCatalog.rejectionReason against the CURRENT resource state
 *   6. rate limit       max-actions-per-resource-per-hour
 *   7. IAM pre-flight   ask IAM whether the call would be permitted
 *   8. record + approve PENDING ActionExecution row, recommendation -> APPROVED
 *   9. execute          exactly one ActionHandler, chosen by enum
 *  10. finish           SUCCEEDED / FAILED, notification, audit entry
 * </pre>
 *
 * Two things are worth pointing out about the design.
 *
 * A failed guardrail does <em>not</em> throw. It writes a BLOCKED {@link ActionExecution} row
 * with the reason and returns it, so "the agent wanted to reboot this instance and the safety
 * layer refused" is visible in the history rather than being an error the UI swallows. The
 * recommendation stays PROPOSED, so the same approval can be retried once the blocker clears
 * (for example after the instance finishes starting).
 *
 * Steps 7 and 9 are network calls, so this class holds no transaction across them. Each
 * database write is its own short transaction through a repository or another service, and the
 * order is chosen so that a crash always leaves a row an operator can see: the attempt is
 * recorded <em>before</em> the provider is called, never after.
 */
@Service
public class ActionExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutionService.class);

    private static final int RATE_WINDOW_MINUTES = 60;

    /**
     * Statuses that count against the hourly rate limit: real attempts only. BLOCKED and
     * CANCELLED rows are not attempts, so a guardrail firing repeatedly cannot itself
     * exhaust the budget.
     */
    private static final List<ActionStatus> ATTEMPTS = List.of(
            ActionStatus.PENDING, ActionStatus.RUNNING,
            ActionStatus.SUCCEEDED, ActionStatus.FAILED);

    private final ActionExecutionRepository actionRepository;
    private final RecommendationRepository recommendationRepository;
    private final RecommendationService recommendationService;
    private final IncidentService incidentService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final ActionCatalog actionCatalog;
    private final CloudProvider cloudProvider;
    private final RemediationProperties remediationProperties;
    private final JsonUtil jsonUtil;
    private final Map<ActionType, ActionHandler> handlers;

    public ActionExecutionService(ActionExecutionRepository actionRepository,
                                RecommendationRepository recommendationRepository,
                                RecommendationService recommendationService,
                                IncidentService incidentService,
                                NotificationService notificationService,
                                AuditService auditService,
                                ActionCatalog actionCatalog,
                                CloudProvider cloudProvider,
                                RemediationProperties remediationProperties,
                                JsonUtil jsonUtil,
                                List<ActionHandler> handlerBeans) {
        this.actionRepository = actionRepository;
        this.recommendationRepository = recommendationRepository;
        this.recommendationService = recommendationService;
        this.incidentService = incidentService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.actionCatalog = actionCatalog;
        this.cloudProvider = cloudProvider;
        this.remediationProperties = remediationProperties;
        this.jsonUtil = jsonUtil;
        this.handlers = indexHandlers(handlerBeans);
    }

    /**
     * Builds the enum -> handler table and fails startup if it is incomplete.
     *
     * A missing handler must not become a runtime surprise during a demo, and a duplicate
     * would make execution depend on bean ordering, so both are refused here.
     */
    private static Map<ActionType, ActionHandler> indexHandlers(List<ActionHandler> handlerBeans) {
        Map<ActionType, ActionHandler> byType = new EnumMap<>(ActionType.class);
        for (ActionHandler handler : handlerBeans) {
            ActionHandler previous = byType.put(handler.actionType(), handler);
            if (previous != null) {
                throw new IllegalStateException(String.format(
                        "Two handlers claim %s: %s and %s", handler.actionType(),
                        previous.getClass().getSimpleName(), handler.getClass().getSimpleName()));
            }
        }
        List<String> missing = new ArrayList<>();
        for (ActionType type : ActionType.values()) {
            if (!byType.containsKey(type)) {
                missing.add(type.name());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "No ActionHandler is registered for: " + String.join(", ", missing));
        }
        log.info("Remediation engine ready with {} safe action handlers", byType.size());
        return Collections.unmodifiableMap(byType);
    }

    /** Everything the guardrails and the handler need, resolved once from the database. */
    private record Plan(
            Long recommendationId,
            Long incidentId,
            Recommendation recommendation,
            Incident incident,
            CloudResource resource,
            ActionType actionType,
            boolean dryRun,
            String note) {
    }

    // ---------------------------------------------------------------------
    // the approval gate
    // ---------------------------------------------------------------------

    /**
     * Approves a recommendation and carries it out. Called only from the ADMIN-only
     * endpoint; the role is re-checked here as well, because this method is the last thing
     * standing between a model's suggestion and a real AWS API call.
     *
     * @return the resulting action row, whatever its outcome — SUCCEEDED, FAILED or BLOCKED
     */
    public ActionExecutionDto approveAndExecute(Long recommendationId,
                                               ApproveRecommendationRequest request) {
        Plan plan = plan(recommendationId, request);

        // 1 + 2. state and whitelist guards. These are programming errors from the caller's
        // point of view, so they are exceptions rather than BLOCKED rows.
        if (plan.recommendation().getStatus() != RecommendationStatus.PROPOSED) {
            throw new BadRequestException(String.format(
                    "Recommendation %d is %s, so it cannot be approved. Only a PROPOSED "
                            + "recommendation may be executed.",
                    recommendationId, plan.recommendation().getStatus()));
        }
        if (plan.actionType() == null) {
            throw new ActionNotAllowedException(String.format(
                    "Recommendation %d has no whitelisted action type (the AI suggested \"%s\"), "
                            + "so there is nothing safe to execute.",
                    recommendationId, plan.recommendation().getAiSuggestedAction()));
        }

        // 3. global kill switch. A dry run is still permitted: it touches nothing.
        if (!remediationProperties.isExecutionEnabled() && !plan.dryRun()) {
            return block(plan, "Remediation is switched off "
                    + "(cloudops.remediation.execution-enabled=false). Nothing was executed.");
        }

        // 4. authority. Defence in depth behind @PreAuthorize on the controller.
        if (remediationProperties.isRequireApproval()
                && plan.actionType().isApprovalRequired()
                && !CurrentUser.isAdmin()) {
            return block(plan, String.format(
                    "%s requires ADMIN approval, but the request came from %s.",
                    plan.actionType().name(), CurrentUser.username()));
        }

        // 5. the catalog, re-checked now: the instance may have changed state since the AI
        // proposed this, and rebooting an instance that has meanwhile stopped is not valid.
        Optional<String> rejection = actionCatalog.rejectionReason(plan.actionType(), plan.resource());
        if (rejection.isPresent()) {
            return block(plan, rejection.get());
        }

        // 6. rate limit, per resource. Stops a flapping metric from producing a reboot loop.
        long recentAttempts = actionRepository.countByResourceAndCreatedAtAfterAndStatusIn(
                plan.resource(),
                Instant.now().minus(RATE_WINDOW_MINUTES, ChronoUnit.MINUTES),
                ATTEMPTS);
        int allowed = remediationProperties.getMaxActionsPerResourcePerHour();
        if (recentAttempts >= allowed) {
            return block(plan, String.format(
                    "Rate limit reached: %d action(s) already ran on %s in the last %d minutes "
                            + "(limit %d). This usually means the underlying problem is not "
                            + "fixable by remediation and needs a human.",
                    recentAttempts, plan.resource().getName(), RATE_WINDOW_MINUTES, allowed));
        }

        // 7. IAM pre-flight, outside any transaction. Only for calls that change something.
        if (!plan.dryRun() && plan.actionType().isMutating()
                && remediationProperties.isIamPreflightEnabled()) {
            PermissionCheck check = preflight(plan.actionType());
            if (check.evaluated() && !check.allowed()) {
                return block(plan, "IAM pre-flight refused the call: "
                        + Texts.defaultIfBlank(check.message(), check.decision()));
            }
        }

        // 8. record the attempt first, then approve. In that order, a crash leaves a visible
        // PENDING row rather than an approved recommendation with no trace of an attempt.
        ActionExecution pending = actionRepository.save(newRow(plan, ActionStatus.PENDING));
        recommendationService.markApproved(recommendationId, plan.note());
        auditService.success("ACTION_APPROVED", "Recommendation", recommendationId, String.format(
                "action=%s resource=%s dryRun=%s note=%s", plan.actionType(),
                plan.resource().getResourceId(), plan.dryRun(),
                Texts.defaultIfBlank(plan.note(), "none")));

        // A real mutation puts the incident into REMEDIATING until the metrics recover
        if (plan.actionType().isMutating() && !plan.dryRun()) {
            incidentService.markStatus(plan.incidentId(), IncidentStatus.REMEDIATING);
        }

        // 9 + 10.
        return run(plan, pending);
    }

    // ---------------------------------------------------------------------
    // execution
    // ---------------------------------------------------------------------

    /**
     * Runs the single predefined operation for this action type. Untransacted on purpose: an
     * EC2 call takes hundreds of milliseconds and must not hold a row lock.
     */
    private ActionExecutionDto run(Plan plan, ActionExecution action) {
        action.setStatus(ActionStatus.RUNNING);
        action.setStartedAt(Instant.now());
        ActionExecution running = actionRepository.save(action);

        long startedAt = System.currentTimeMillis();
        ProviderActionResult result;
        try {
            if (plan.dryRun()) {
                // The whole path above has already run — validation, approval, audit. This is
                // the one thing a dry run skips, and it is the only thing that touches AWS.
                result = ProviderActionResult.ok(
                        "DRY RUN: validated, approved and audited, but no provider call was made. "
                                + "A real execution would: " + plan.actionType().getDescription(),
                        null);
            } else {
                ActionHandler handler = handlers.get(plan.actionType());
                result = handler.execute(new ActionHandler.Target(
                        running.getId(),
                        plan.incidentId(),
                        plan.resource().getId(),
                        plan.resource().getResourceId(),
                        plan.resource().getName(),
                        plan.actionType(),
                        plan.dryRun()));
            }
        } catch (RuntimeException ex) {
            log.error("Handler for {} threw on incident {}: {}", plan.actionType(),
                    plan.incidentId(), ex.getMessage(), ex);
            result = ProviderActionResult.failed(
                    Texts.redactSecrets(Texts.defaultIfBlank(ex.getMessage(),
                            ex.getClass().getSimpleName())));
        }

        running.setFinishedAt(Instant.now());
        running.setDurationMs(System.currentTimeMillis() - startedAt);
        return result.success() ? succeed(plan, running, result) : fail(plan, running, result);
    }

    private ActionExecutionDto succeed(Plan plan, ActionExecution action, ProviderActionResult result) {
        action.setStatus(ActionStatus.SUCCEEDED);
        action.setResultMessage(Texts.truncate(result.message(), 2000));
        action.setProviderRequestId(Texts.truncate(result.providerRequestId(), 100));
        ActionExecution saved = actionRepository.save(action);

        recommendationService.markExecuted(plan.recommendationId());
        notificationService.actionExecuted(saved);
        auditService.success("ACTION_EXECUTED", "ActionExecution", saved.getId(), String.format(
                "action=%s resource=%s dryRun=%s durationMs=%s requestId=%s",
                plan.actionType(), plan.resource().getResourceId(), plan.dryRun(),
                saved.getDurationMs(), Texts.defaultIfBlank(saved.getProviderRequestId(), "n/a")));
        log.info("Action {} on {} succeeded for incident {} (dryRun={})", plan.actionType(),
                plan.resource().getResourceId(), plan.incidentId(), plan.dryRun());

        // A real mutation leaves the incident REMEDIATING: only recovered metrics resolve it,
        // never the fact that the call returned 200. Everything else goes back to the queue.
        if (!plan.actionType().isMutating() || plan.dryRun()) {
            incidentService.markStatus(plan.incidentId(), IncidentStatus.OPEN);
        }
        return DtoMapper.toActionDto(saved);
    }

    private ActionExecutionDto fail(Plan plan, ActionExecution action, ProviderActionResult result) {
        String message = Texts.defaultIfBlank(result.message(), "The provider call did not succeed.");
        action.setStatus(ActionStatus.FAILED);
        action.setErrorMessage(Texts.truncate(message, 2000));
        ActionExecution saved = actionRepository.save(action);

        recommendationService.markFailed(plan.recommendationId(), message);
        notificationService.actionFailed(saved);
        auditService.failure("ACTION_FAILED", "ActionExecution", saved.getId(), String.format(
                "action=%s resource=%s error=%s", plan.actionType(),
                plan.resource().getResourceId(), Texts.truncate(message, 300)));
        log.warn("Action {} on {} failed for incident {}: {}", plan.actionType(),
                plan.resource().getResourceId(), plan.incidentId(), message);

        // FAILED is an active status, so the incident stays in the operator's queue
        incidentService.markStatus(plan.incidentId(), IncidentStatus.FAILED);
        return DtoMapper.toActionDto(saved);
    }

    /**
     * A guardrail refused. Recorded as a BLOCKED row — the refusal is part of the story of
     * the incident, and it is what a reviewer will want to see. The recommendation is left
     * PROPOSED so the approval can be retried when the blocker no longer applies.
     */
    private ActionExecutionDto block(Plan plan, String reason) {
        ActionExecution action = newRow(plan, ActionStatus.BLOCKED);
        action.setBlockedReason(Texts.truncate(reason, 500));
        action.setFinishedAt(Instant.now());
        action.setDurationMs(0L);
        ActionExecution saved = actionRepository.save(action);

        notificationService.actionBlocked(saved);
        auditService.denied("ACTION_BLOCKED", "Recommendation", plan.recommendationId(),
                plan.actionType() + " on " + plan.resource().getResourceId() + ": " + reason);
        log.warn("Guardrail blocked {} on {} (incident {}): {}", plan.actionType(),
                plan.resource().getResourceId(), plan.incidentId(), reason);
        return DtoMapper.toActionDto(saved);
    }

    // ---------------------------------------------------------------------
    // reads
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ActionExecutionDto> listForIncident(Long incidentId) {
        Incident incident = incidentService.get(incidentId);
        return DtoMapper.toActionDtos(actionRepository.findByIncidentOrderByCreatedAtDesc(incident));
    }

    /** The history screen: every action ever attempted, newest first. */
    @Transactional(readOnly = true)
    public Page<ActionExecutionDto> history(ActionStatus status, Pageable pageable) {
        Page<ActionExecution> page = status == null
                ? actionRepository.findAllByOrderByCreatedAtDesc(pageable)
                : actionRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        return page.map(DtoMapper::toActionDto);
    }

    @Transactional(readOnly = true)
    public ActionExecutionDto getDto(Long actionId) {
        return DtoMapper.toActionDto(actionRepository.findById(actionId)
                .orElseThrow(() -> NotFoundException.of("Action", actionId)));
    }

    /** The whitelist itself, so an approver can read what each action does before allowing it. */
    public List<ActionCatalogEntryDto> catalog() {
        return actionCatalog.catalog();
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /**
     * Resolves the recommendation, its incident and the target resource up front.
     *
     * The incident is fetched through {@link IncidentService#get} rather than by navigating
     * {@code recommendation.getIncident()}, because that association is LAZY and this method's
     * result is used outside any transaction.
     */
    private Plan plan(Long recommendationId, ApproveRecommendationRequest request) {
        Recommendation recommendation = recommendationService.get(recommendationId);
        Long incidentId = recommendationRepository.findIncidentIdById(recommendationId)
                .orElseThrow(() -> NotFoundException.of("Incident for recommendation", recommendationId));
        Incident incident = incidentService.get(incidentId);

        boolean requestedDryRun = request != null && Boolean.TRUE.equals(request.dryRun());
        return new Plan(
                recommendationId,
                incidentId,
                recommendation,
                incident,
                incident.getResource(),
                recommendation.getActionType(),
                remediationProperties.isForceDryRun() || requestedDryRun,
                request == null ? null : request.note());
    }

    /**
     * A new action row. Note what {@code parametersJson} contains: the action type, the
     * resource identifier we looked up, the incident id and the dry-run flag. There is no
     * field here for a command, and nothing in it comes from model output.
     */
    private ActionExecution newRow(Plan plan, ActionStatus status) {
        ActionExecution action = new ActionExecution();
        action.setIncident(plan.incident());
        action.setRecommendation(plan.recommendation());
        action.setResource(plan.resource());
        action.setActionType(plan.actionType());
        action.setStatus(status);
        action.setDryRun(plan.dryRun());
        action.setRequestedBy(CurrentUser.username());
        action.setApprovedBy(CurrentUser.username());

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("actionType", plan.actionType().name());
        parameters.put("iamAction", Texts.defaultIfBlank(iamActionFor(plan.actionType()), "none"));
        parameters.put("resourceId", plan.resource().getResourceId());
        parameters.put("resourceType", String.valueOf(plan.resource().getType()));
        parameters.put("incidentId", plan.incidentId());
        parameters.put("dryRun", plan.dryRun());
        action.setParametersJson(jsonUtil.toJson(parameters));
        return action;
    }

    /**
     * Asks IAM whether the call would be allowed, before making it. An inconclusive answer
     * ({@code evaluated == false}) does not block: AWS itself is the real enforcement point,
     * and refusing on "we could not check" would make the agent unusable on assumed roles.
     */
    private PermissionCheck preflight(ActionType actionType) {
        String iamAction = iamActionFor(actionType);
        if (iamAction == null) {
            return PermissionCheck.notEvaluated(actionType + " makes no cloud API call.");
        }
        try {
            return cloudProvider.checkPermission(iamAction, "*");
        } catch (RuntimeException ex) {
            log.debug("IAM pre-flight for {} could not run: {}", iamAction, ex.getMessage());
            return PermissionCheck.notEvaluated("Pre-flight failed: "
                    + Texts.redactSecrets(String.valueOf(ex.getMessage())));
        }
    }

    /** The one IAM permission each mutating action needs; null when it touches no cloud API. */
    private String iamActionFor(ActionType actionType) {
        return switch (actionType) {
            case REBOOT_EC2_INSTANCE -> "ec2:RebootInstances";
            case STOP_EC2_INSTANCE -> "ec2:StopInstances";
            case START_EC2_INSTANCE -> "ec2:StartInstances";
            case TAG_FOR_REVIEW -> "ec2:CreateTags";
            case COLLECT_MORE_METRICS -> "cloudwatch:GetMetricStatistics";
            case NOTIFY_ONCALL, RECOMMEND_MANUAL_SCALE_UP, NO_ACTION -> null;
        };
    }
}
