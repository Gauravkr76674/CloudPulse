package com.cloudops.agent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cloudops.agent.ai.model.IncidentContext;
import com.cloudops.agent.config.MonitoringProperties;
import com.cloudops.agent.dto.CreateIncidentRequest;
import com.cloudops.agent.dto.IncidentDetailDto;
import com.cloudops.agent.dto.IncidentDto;
import com.cloudops.agent.entity.ActionExecution;
import com.cloudops.agent.entity.CloudResource;
import com.cloudops.agent.entity.Incident;
import com.cloudops.agent.entity.IncidentStatus;
import com.cloudops.agent.entity.Metric;
import com.cloudops.agent.entity.Recommendation;
import com.cloudops.agent.entity.RecommendationStatus;
import com.cloudops.agent.entity.Severity;
import com.cloudops.agent.exception.BadRequestException;
import com.cloudops.agent.exception.NotFoundException;
import com.cloudops.agent.repository.CloudResourceRepository;
import com.cloudops.agent.repository.IncidentRepository;
import com.cloudops.agent.repository.MetricRepository;
import com.cloudops.agent.repository.RecommendationRepository;
import com.cloudops.agent.security.CurrentUser;
import com.cloudops.agent.service.AnomalyDetectionService.AnomalyVerdict;
import com.cloudops.agent.util.DtoMapper;
import com.cloudops.agent.util.Texts;

import jakarta.persistence.criteria.Predicate;

/**
 * Owns the incident lifecycle: OPEN → ANALYZING → AWAITING_APPROVAL → REMEDIATING →
 * RESOLVED → CLOSED, plus the FAILED branch when a remediation does not work.
 *
 * The important behaviour here is <b>deduplication</b>. A breach that repeats every five
 * minutes must not create a new incident every five minutes, or the dashboard becomes
 * useless during exactly the outage it exists for. Instead, (resource + metric + rule)
 * forms a {@code dedupKey} that is unique among <em>active</em> incidents, so repeats roll
 * into one row with a rising {@code occurrenceCount} and an escalating severity.
 *
 * This class also assembles the {@link IncidentContext} handed to the AI. Note what goes
 * into it: incident facts, metric history, resource metadata and the allowed action
 * catalog. No credentials, no endpoints, no ability to query anything further.
 */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    /** Statuses that still need someone's attention. */
    public static final List<IncidentStatus> ACTIVE_STATUSES = List.of(
            IncidentStatus.OPEN,
            IncidentStatus.ANALYZING,
            IncidentStatus.AWAITING_APPROVAL,
            IncidentStatus.REMEDIATING,
            IncidentStatus.FAILED);

    /** Value of active_flag while an incident holds its dedup slot. */
    private static final long ACTIVE_FLAG = 1L;

    private static final int CONTEXT_METRIC_LINES = 20;
    private static final int CONTEXT_ACTION_LINES = 5;

    private final IncidentRepository incidentRepository;
    private final CloudResourceRepository resourceRepository;
    private final MetricRepository metricRepository;
    private final RecommendationRepository recommendationRepository;
    private final AnomalyDetectionService anomalyDetectionService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final ActionCatalog actionCatalog;
    private final MonitoringProperties monitoringProperties;

    public IncidentService(IncidentRepository incidentRepository,
                          CloudResourceRepository resourceRepository,
                          MetricRepository metricRepository,
                          RecommendationRepository recommendationRepository,
                          AnomalyDetectionService anomalyDetectionService,
                          NotificationService notificationService,
                          AuditService auditService,
                          ActionCatalog actionCatalog,
                          MonitoringProperties monitoringProperties) {
        this.incidentRepository = incidentRepository;
        this.resourceRepository = resourceRepository;
        this.metricRepository = metricRepository;
        this.recommendationRepository = recommendationRepository;
        this.anomalyDetectionService = anomalyDetectionService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.actionCatalog = actionCatalog;
        this.monitoringProperties = monitoringProperties;
    }

    /** Result of feeding a breach into the incident tracker. */
    public record BreachResult(Incident incident, boolean opened) {
    }

    // ---------------------------------------------------------------------
    // detection → incident
    // ---------------------------------------------------------------------

    /**
     * Records an anomalous datapoint against an incident, opening one if this is the first
     * time the rule has fired for this resource and metric.
     */
    @Transactional
    public BreachResult registerBreach(CloudResource resource, Metric metric, AnomalyVerdict verdict) {
        String dedupKey = dedupKey(resource, metric.getMetricName(), verdict.ruleName());
        Optional<Incident> existing = incidentRepository.findByDedupKeyAndActiveFlag(dedupKey, ACTIVE_FLAG);

        if (existing.isPresent()) {
            Incident incident = existing.get();
            Severity previous = incident.getSeverity();

            incident.setOccurrenceCount(incident.getOccurrenceCount() + 1);
            incident.setLastSeenAt(Instant.now());
            incident.setTriggerValue(metric.getValue());
            incident.setThresholdValue(verdict.thresholdValue());

            // Severity only ever climbs while an incident is open; a human decides it is over
            if (verdict.severity() != null && verdict.severity().isAtLeast(incident.getSeverity())
                    && verdict.severity() != incident.getSeverity()) {
                incident.setSeverity(verdict.severity());
            }
            if (incident.getStatus() == IncidentStatus.FAILED) {
                // A previous remediation failed and the problem is still here
                incident.setStatus(IncidentStatus.OPEN);
            }

            Incident saved = incidentRepository.save(incident);
            if (saved.getSeverity() != previous) {
                notificationService.severityEscalated(saved, previous);
                auditService.success("INCIDENT_ESCALATED", "Incident", saved.getId(),
                        "Severity " + previous + " -> " + saved.getSeverity()
                                + " after " + saved.getOccurrenceCount() + " occurrences");
            }
            log.debug("Incident {} now at {} occurrences", saved.getId(), saved.getOccurrenceCount());
            return new BreachResult(saved, false);
        }

        Incident incident = new Incident();
        incident.setResource(resource);
        incident.setTitle(buildTitle(resource, metric, verdict));
        incident.setDescription(buildDescription(resource, metric, verdict));
        incident.setStatus(IncidentStatus.OPEN);
        incident.setSeverity(verdict.severity() == null ? Severity.MEDIUM : verdict.severity());
        incident.setMetricName(metric.getMetricName());
        incident.setRuleName(verdict.ruleName());
        incident.setTriggerValue(metric.getValue());
        incident.setThresholdValue(verdict.thresholdValue());
        incident.setDedupKey(dedupKey);
        incident.setActiveFlag(ACTIVE_FLAG);
        incident.setOccurrenceCount(verdict.consecutiveBreaches() < 1 ? 1 : verdict.consecutiveBreaches());

        Incident saved = incidentRepository.save(incident);
        log.info("Opened incident {} ({}) for {} on {}", saved.getId(), saved.getSeverity(),
                metric.getMetricName(), resource.getResourceId());

        notificationService.incidentOpened(saved);
        auditService.success("INCIDENT_OPENED", "Incident", saved.getId(),
                verdict.reason() + " [rule=" + verdict.ruleName() + "]");
        return new BreachResult(saved, true);
    }

    /**
     * Closes the loop: when the metric has been healthy for as many datapoints as it took
     * to open the incident, resolve it automatically and note why.
     *
     * @return the number of incidents auto-resolved
     */
    @Transactional
    public int checkRecovery(CloudResource resource, String metricName, String statistic) {
        Double threshold = anomalyDetectionService.thresholdFor(resource, metricName);
        if (threshold == null) {
            return 0;
        }

        int required = anomalyDetectionService.requiredBreaches(resource);
        int healthy = anomalyDetectionService.countConsecutiveBelow(
                resource, metricName, statistic, threshold);
        if (healthy < required) {
            return 0;
        }

        List<Incident> active = incidentRepository.findByResourceAndStatusIn(resource, ACTIVE_STATUSES);
        int resolved = 0;
        for (Incident incident : active) {
            if (!metricName.equals(incident.getMetricName())) {
                continue;
            }
            String note = String.format(
                    "Auto-resolved: %s has been at or below %s for %d consecutive datapoints.",
                    metricName, Texts.number(threshold), healthy);
            closeOut(incident, IncidentStatus.RESOLVED, CurrentUser.SYSTEM, note);
            resolved++;
        }
        return resolved;
    }

    // ---------------------------------------------------------------------
    // lifecycle transitions
    // ---------------------------------------------------------------------

    /** Used by the AI and remediation pipelines to advance an incident. */
    @Transactional
    public Incident markStatus(Long incidentId, IncidentStatus status) {
        Incident incident = require(incidentId);
        if (incident.getStatus().isTerminal() && !status.isTerminal()) {
            log.debug("Refusing to move terminal incident {} back to {}", incidentId, status);
            return incident;
        }
        incident.setStatus(status);
        return incidentRepository.save(incident);
    }

    /** Escalates severity when the AI judges the situation worse than the rule did. */
    @Transactional
    public Incident applyAiSeverity(Long incidentId, Severity suggested) {
        Incident incident = require(incidentId);
        if (suggested == null || incident.getStatus().isTerminal()) {
            return incident;
        }
        if (suggested.isAtLeast(incident.getSeverity()) && suggested != incident.getSeverity()) {
            Severity previous = incident.getSeverity();
            incident.setSeverity(suggested);
            Incident saved = incidentRepository.save(incident);
            auditService.success("INCIDENT_SEVERITY_AI", "Incident", incidentId,
                    "AI raised severity " + previous + " -> " + suggested);
            return saved;
        }
        // Downgrades are deliberately not automatic: a model should not quieten an alarm
        log.debug("AI suggested {} for incident {} which is not an escalation; keeping {}",
                suggested, incidentId, incident.getSeverity());
        return incident;
    }

    @Transactional
    public Incident acknowledge(Long incidentId) {
        Incident incident = require(incidentId);
        if (incident.getStatus().isTerminal()) {
            throw new BadRequestException("Incident " + incidentId + " is already "
                    + incident.getStatus() + " and cannot be acknowledged.");
        }
        incident.setAcknowledgedAt(Instant.now());
        incident.setAcknowledgedBy(CurrentUser.username());
        Incident saved = incidentRepository.save(incident);
        auditService.success("INCIDENT_ACKNOWLEDGED", "Incident", incidentId, null);
        return saved;
    }

    @Transactional
    public Incident resolve(Long incidentId, String note) {
        Incident incident = require(incidentId);
        if (incident.getStatus() == IncidentStatus.CLOSED) {
            throw new BadRequestException("Incident " + incidentId + " is closed.");
        }
        closeOut(incident, IncidentStatus.RESOLVED, CurrentUser.username(),
                Texts.defaultIfBlank(note, "Resolved manually."));
        return incident;
    }

    @Transactional
    public Incident close(Long incidentId, String note) {
        Incident incident = require(incidentId);
        closeOut(incident, IncidentStatus.CLOSED, CurrentUser.username(),
                Texts.defaultIfBlank(note, incident.getResolutionNote()));
        return incident;
    }

    /**
     * Shared terminal transition: stamps who/when, releases the dedup slot so a future
     * breach can open a fresh incident, and expires anything still awaiting a decision.
     */
    private void closeOut(Incident incident, IncidentStatus status, String actor, String note) {
        incident.setStatus(status);
        incident.setResolvedAt(Instant.now());
        incident.setResolvedBy(actor);
        incident.setResolutionNote(note);
        incident.releaseActiveSlot();
        incidentRepository.save(incident);

        expirePendingRecommendations(incident, "Incident " + status.name().toLowerCase());

        notificationService.incidentResolved(incident);
        auditService.success("INCIDENT_" + status.name(), "Incident", incident.getId(), note);
        log.info("Incident {} {} by {}", incident.getId(), status, actor);
    }

    private void expirePendingRecommendations(Incident incident, String reason) {
        List<Recommendation> pending = recommendationRepository.findByIncidentAndStatus(
                incident, RecommendationStatus.PROPOSED);
        for (Recommendation recommendation : pending) {
            recommendation.setStatus(RecommendationStatus.EXPIRED);
            recommendation.setRejectionReason(Texts.truncate(reason, 500));
            recommendationRepository.save(recommendation);
        }
        if (!pending.isEmpty()) {
            log.debug("Expired {} pending recommendation(s) on incident {}",
                    pending.size(), incident.getId());
        }
    }

    // ---------------------------------------------------------------------
    // manual creation and reads
    // ---------------------------------------------------------------------

    @Transactional
    public Incident create(CreateIncidentRequest request) {
        CloudResource resource = resourceRepository.findById(request.resourceId())
                .orElseThrow(() -> NotFoundException.of("CloudResource", request.resourceId()));

        Incident incident = new Incident();
        incident.setResource(resource);
        incident.setTitle(Texts.truncate(request.title(), 200));
        incident.setDescription(request.description());
        incident.setStatus(IncidentStatus.OPEN);
        incident.setSeverity(request.severity() == null ? Severity.MEDIUM : request.severity());
        incident.setMetricName(Texts.defaultIfBlank(request.metricName(), "MANUAL"));
        incident.setRuleName("MANUAL");
        incident.setActiveFlag(ACTIVE_FLAG);
        // Manual incidents get a unique dedup key so they never collide with detector rows
        incident.setDedupKey(Texts.truncate("manual|" + resource.getResourceId() + "|"
                + Instant.now().toEpochMilli(), 200));

        Incident saved = incidentRepository.save(incident);
        notificationService.incidentOpened(saved);
        auditService.success("INCIDENT_CREATED", "Incident", saved.getId(),
                "Raised manually for " + resource.getResourceId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Incident get(Long incidentId) {
        return require(incidentId);
    }

    // -----------------------------------------------------------------------
    // DTO-returning variants.
    //
    // IncidentDto carries counts of the analyses/recommendations/actions attached to an
    // incident, and those are LAZY collections. Mapping therefore has to happen while the
    // Hibernate session is still open. spring.jpa.open-in-view is false (deliberately), so
    // a controller that maps after the service call has already lost the session and gets
    // a LazyInitializationException -> HTTP 500. These wrappers keep the mapping inside
    // the transaction; the entity-returning methods above stay for internal callers.
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<IncidentDto> searchDtos(IncidentStatus status, Severity severity, Long resourceId,
                                        boolean activeOnly, Pageable pageable) {
        return search(status, severity, resourceId, activeOnly, pageable)
                .map(DtoMapper::toIncidentDto);
    }

    @Transactional
    public IncidentDto createDto(CreateIncidentRequest request) {
        return DtoMapper.toIncidentDto(create(request));
    }

    @Transactional
    public IncidentDto acknowledgeDto(Long incidentId) {
        return DtoMapper.toIncidentDto(acknowledge(incidentId));
    }

    @Transactional
    public IncidentDto resolveDto(Long incidentId, String note) {
        return DtoMapper.toIncidentDto(resolve(incidentId, note));
    }

    @Transactional
    public IncidentDto closeDto(Long incidentId, String note) {
        return DtoMapper.toIncidentDto(close(incidentId, note));
    }

    @Transactional(readOnly = true)
    public Page<Incident> search(IncidentStatus status, Severity severity, Long resourceId,
                                 boolean activeOnly, Pageable pageable) {
        Specification<Incident> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (severity != null) {
                predicates.add(builder.equal(root.get("severity"), severity));
            }
            if (resourceId != null) {
                predicates.add(builder.equal(root.get("resource").get("id"), resourceId));
            }
            if (activeOnly) {
                predicates.add(root.get("status").in(ACTIVE_STATUSES));
            }
            return predicates.isEmpty()
                    ? builder.conjunction()
                    : builder.and(predicates.toArray(new Predicate[0]));
        };
        return incidentRepository.findAll(specification, pageable);
    }

    @Transactional(readOnly = true)
    public IncidentDetailDto detail(Long incidentId) {
        Incident incident = require(incidentId);
        List<Metric> metrics = contextMetrics(incident);
        return new IncidentDetailDto(
                DtoMapper.toIncidentDto(incident),
                incident.getDescription(),
                incident.getResolutionNote(),
                DtoMapper.toAnalysisDtos(incident.getAnalyses()),
                DtoMapper.toRecommendationDtos(incident.getRecommendations()),
                DtoMapper.toActionDtos(incident.getActions()),
                DtoMapper.toMetricDtos(metrics));
    }

    @Transactional(readOnly = true)
    public List<Incident> activeIncidents() {
        return incidentRepository.findByStatusIn(ACTIVE_STATUSES);
    }

    // ---------------------------------------------------------------------
    // AI context assembly
    // ---------------------------------------------------------------------

    /**
     * Builds the complete, read-only picture the model is given. Everything here comes from
     * our own database; the model gets facts and a closed action list, and nothing else.
     */
    @Transactional(readOnly = true)
    public IncidentContext buildAiContext(Long incidentId) {
        Incident incident = require(incidentId);
        CloudResource resource = incident.getResource();

        return new IncidentContext(
                incident.getId(),
                incident.getTitle(),
                String.valueOf(incident.getSeverity()),
                String.valueOf(incident.getStatus()),
                incident.getRuleName(),
                incident.getMetricName(),
                incident.getTriggerValue(),
                incident.getThresholdValue(),
                incident.getOccurrenceCount(),
                String.valueOf(incident.getOpenedAt()),
                resource.getName(),
                resource.getResourceId(),
                String.valueOf(resource.getType()),
                resource.getInstanceType(),
                resource.getRegion(),
                resource.getAvailabilityZone(),
                String.valueOf(resource.getStatus()),
                resource.getTagsJson(),
                metricLines(incident),
                actionLines(incident),
                actionCatalog.allowedNamesFor(resource));
    }

    /** One line per datapoint, oldest first, in a shape the stub parser also understands. */
    private List<String> metricLines(Incident incident) {
        List<String> lines = new ArrayList<>();

        for (Metric metric : contextMetrics(incident)) {
            lines.add(String.format("%s %s %s value=%s unit=%s anomalous=%s",
                    metric.getTimestamp(),
                    metric.getMetricName(),
                    metric.getStatistic(),
                    Texts.number(metric.getValue()),
                    Texts.defaultIfBlank(metric.getUnit(), "n/a"),
                    metric.isAnomalous()));
        }

        // Other metrics of the same resource, so the model can tell a traffic surge from a
        // runaway process instead of guessing.
        List<Metric> latest = metricRepository.findLatestPerMetric(incident.getResource());
        for (Metric metric : latest) {
            if (!metric.getMetricName().equals(incident.getMetricName())) {
                lines.add(String.format("%s %s %s latest=%s unit=%s",
                        metric.getTimestamp(),
                        metric.getMetricName(),
                        metric.getStatistic(),
                        Texts.number(metric.getValue()),
                        Texts.defaultIfBlank(metric.getUnit(), "n/a")));
            }
        }
        return lines;
    }

    private List<String> actionLines(Incident incident) {
        List<ActionExecution> actions = new ArrayList<>(incident.getActions());
        actions.sort(Comparator.comparing(ActionExecution::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<String> lines = new ArrayList<>();
        int from = Math.max(0, actions.size() - CONTEXT_ACTION_LINES);
        for (ActionExecution action : actions.subList(from, actions.size())) {
            lines.add(String.format("%s %s status=%s dryRun=%s outcome=%s",
                    action.getCreatedAt(),
                    action.getActionType(),
                    action.getStatus(),
                    action.isDryRun(),
                    Texts.truncate(Texts.defaultIfBlank(action.getResultMessage(),
                            Texts.defaultIfBlank(action.getErrorMessage(),
                                    Texts.defaultIfBlank(action.getBlockedReason(), "n/a"))), 160)));
        }
        return lines;
    }

    /** The datapoints shown on the detail screen and given to the model. */
    private List<Metric> contextMetrics(Incident incident) {
        Instant from = (incident.getOpenedAt() == null ? Instant.now() : incident.getOpenedAt())
                .minus(Math.max(monitoringProperties.getLookbackMinutes(), 60L), ChronoUnit.MINUTES);

        List<Metric> metrics = metricRepository
                .findByResourceAndMetricNameAndTimestampAfterOrderByTimestampAsc(
                        incident.getResource(), incident.getMetricName(), from);

        if (metrics.size() <= CONTEXT_METRIC_LINES) {
            return metrics;
        }
        return new ArrayList<>(metrics.subList(metrics.size() - CONTEXT_METRIC_LINES, metrics.size()));
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private Incident require(Long incidentId) {
        return incidentRepository.findById(incidentId)
                .orElseThrow(() -> NotFoundException.of("Incident", incidentId));
    }

    private String dedupKey(CloudResource resource, String metricName, String ruleName) {
        return Texts.truncate(resource.getResourceId() + "|" + metricName + "|" + ruleName, 200);
    }

    private String buildTitle(CloudResource resource, Metric metric, AnomalyVerdict verdict) {
        String threshold = verdict.thresholdValue() == null
                ? "its baseline"
                : Texts.number(verdict.thresholdValue());
        return Texts.truncate(String.format("%s on %s at %s (limit %s)",
                metric.getMetricName(), resource.getName(),
                Texts.number(metric.getValue()), threshold), 200);
    }

    private String buildDescription(CloudResource resource, Metric metric, AnomalyVerdict verdict) {
        StringBuilder description = new StringBuilder();
        description.append(verdict.reason()).append("\n\n")
                .append("Resource: ").append(resource.getName())
                .append(" (").append(resource.getResourceId()).append(")\n")
                .append("Region: ").append(resource.getRegion()).append('\n')
                .append("Instance type: ")
                .append(Texts.defaultIfBlank(resource.getInstanceType(), "n/a")).append('\n')
                .append("Metric: ").append(metric.getMetricName())
                .append(" (").append(metric.getStatistic()).append(")\n")
                .append("Value: ").append(Texts.number(metric.getValue()))
                .append(' ').append(Texts.defaultIfBlank(metric.getUnit(), "")).append('\n')
                .append("Threshold: ").append(Texts.number(verdict.thresholdValue())).append('\n')
                .append("Datapoint time: ").append(metric.getTimestamp()).append('\n');

        if (verdict.baselineMean() != null) {
            description.append("Baseline mean: ").append(Texts.number(verdict.baselineMean()))
                    .append(", std dev: ").append(Texts.number(verdict.baselineStdDev()))
                    .append(", z-score: ").append(Texts.number(verdict.zScore())).append('\n');
        }
        return description.toString();
    }
}
