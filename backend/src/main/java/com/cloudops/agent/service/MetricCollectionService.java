package com.cloudops.agent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.cloudops.agent.aws.CloudProvider;
import com.cloudops.agent.aws.model.MetricSample;
import com.cloudops.agent.config.MonitoringProperties;
import com.cloudops.agent.dto.CollectionResultDto;
import com.cloudops.agent.entity.CloudResource;
import com.cloudops.agent.service.MetricIngestionService.IngestionOutcome;
import com.cloudops.agent.util.Texts;

/**
 * The heartbeat of the whole system: every {@code cloudops.monitoring.poll-interval-ms} it
 * asks the provider for fresh CloudWatch datapoints for each monitored resource, hands them
 * to {@link MetricIngestionService} and then — once that transaction has committed — kicks
 * off AI analysis for any incident that was just opened.
 *
 * Deliberately not transactional. Provider calls happen on this thread with no database
 * transaction held, and each resource is polled inside its own try/catch so one unreachable
 * instance cannot stop the sweep.
 */
@Service
public class MetricCollectionService {

    private static final Logger log = LoggerFactory.getLogger(MetricCollectionService.class);

    private final CloudProvider cloudProvider;
    private final CloudResourceService resourceService;
    private final MetricIngestionService ingestionService;
    private final AiAnalysisService aiAnalysisService;
    private final MonitoringProperties monitoringProperties;

    public MetricCollectionService(CloudProvider cloudProvider,
                                  CloudResourceService resourceService,
                                  MetricIngestionService ingestionService,
                                  AiAnalysisService aiAnalysisService,
                                  MonitoringProperties monitoringProperties) {
        this.cloudProvider = cloudProvider;
        this.resourceService = resourceService;
        this.ingestionService = ingestionService;
        this.aiAnalysisService = aiAnalysisService;
        this.monitoringProperties = monitoringProperties;
    }

    // ---------------------------------------------------------------------
    // scheduled entry points
    // ---------------------------------------------------------------------

    @Scheduled(
            fixedDelayString = "${cloudops.monitoring.poll-interval-ms:300000}",
            initialDelayString = "${cloudops.monitoring.initial-delay-ms:20000}")
    public void scheduledCollection() {
        if (!monitoringProperties.isEnabled()) {
            log.debug("Monitoring is disabled; skipping scheduled collection");
            return;
        }
        try {
            CollectionResultDto result = collectAll();
            log.info("Poll finished: {} resource(s), {} datapoint(s), {} anomaly(ies), "
                            + "{} incident(s) opened in {} ms",
                    result.resourcesPolled(), result.datapointsStored(),
                    result.anomaliesDetected(), result.incidentsOpened(), result.durationMs());
        } catch (RuntimeException ex) {
            // A scheduled method that throws is silently dropped by the scheduler; log it
            log.error("Scheduled collection failed: {}",
                    Texts.redactSecrets(String.valueOf(ex.getMessage())), ex);
        }
    }

    /** Retention cleanup, nightly at 03:30. */
    @Scheduled(cron = "${cloudops.monitoring.purge-cron:0 30 3 * * *}")
    public void scheduledPurge() {
        try {
            Instant cutoff = Instant.now().minus(
                    Math.max(1, monitoringProperties.getRetentionDays()), ChronoUnit.DAYS);
            int deleted = ingestionService.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("Purged {} metric row(s) older than {}", deleted, cutoff);
            }
        } catch (RuntimeException ex) {
            log.warn("Metric purge failed: {}", ex.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // collection
    // ---------------------------------------------------------------------

    /** Polls every monitored resource once. Also exposed as POST /api/metrics/collect. */
    public CollectionResultDto collectAll() {
        Instant startedAt = Instant.now();
        List<CloudResource> resources = resourceService.monitoredResources();
        List<String> errors = new ArrayList<>();

        int datapoints = 0;
        int anomalies = 0;
        int openedTotal = 0;
        int updatedTotal = 0;
        List<Long> openedIncidents = new ArrayList<>();

        for (CloudResource resource : resources) {
            try {
                IngestionOutcome outcome = pollOne(resource);
                datapoints += outcome.stored();
                anomalies += outcome.anomalies();
                openedTotal += outcome.incidentsOpened();
                updatedTotal += outcome.incidentsUpdated();
                openedIncidents.addAll(outcome.openedIncidentIds());
            } catch (RuntimeException ex) {
                String message = resource.getResourceId() + ": "
                        + Texts.redactSecrets(String.valueOf(ex.getMessage()));
                errors.add(message);
                log.warn("Polling {} failed: {}", resource.getResourceId(), message);
            }
        }

        triggerAnalysis(openedIncidents);

        Instant finishedAt = Instant.now();
        return new CollectionResultDto(
                cloudProvider.providerName(),
                resources.size(),
                datapoints,
                anomalies,
                openedTotal,
                updatedTotal,
                startedAt,
                finishedAt,
                finishedAt.toEpochMilli() - startedAt.toEpochMilli(),
                errors);
    }

    /**
     * Polls one resource immediately. Used by the UI refresh button and by the
     * COLLECT_MORE_METRICS remediation action.
     */
    public CollectionResultDto collectResource(Long resourceId) {
        Instant startedAt = Instant.now();
        CloudResource resource = resourceService.get(resourceId);
        List<String> errors = new ArrayList<>();
        IngestionOutcome outcome = IngestionOutcome.empty();

        try {
            outcome = pollOne(resource);
            triggerAnalysis(outcome.openedIncidentIds());
        } catch (RuntimeException ex) {
            errors.add(Texts.redactSecrets(String.valueOf(ex.getMessage())));
            log.warn("On-demand poll of {} failed: {}", resource.getResourceId(), ex.getMessage());
        }

        Instant finishedAt = Instant.now();
        return new CollectionResultDto(
                cloudProvider.providerName(),
                1,
                outcome.stored(),
                outcome.anomalies(),
                outcome.incidentsOpened(),
                outcome.incidentsUpdated(),
                startedAt,
                finishedAt,
                finishedAt.toEpochMilli() - startedAt.toEpochMilli(),
                errors);
    }

    /**
     * Fetches every configured metric for one resource and ingests them as a single batch.
     * The provider call happens here, outside any transaction.
     */
    private IngestionOutcome pollOne(CloudResource resource) {
        Instant end = Instant.now();
        Instant start = end.minus(
                Math.max(10, monitoringProperties.getLookbackMinutes()), ChronoUnit.MINUTES);
        int period = Math.max(60, monitoringProperties.getCloudwatchPeriodSeconds());

        List<MetricSample> samples = new ArrayList<>();
        for (String metricName : monitoringProperties.getMetricNames()) {
            String statistic = statisticFor(metricName);
            List<MetricSample> fetched = cloudProvider.fetchMetricSamples(
                    resource.getResourceId(), metricName, statistic, start, end, period);
            if (fetched != null) {
                samples.addAll(fetched);
            }
        }

        IngestionOutcome outcome = ingestionService.ingest(resource.getId(), samples);
        resourceService.markChecked(resource.getId());
        return outcome;
    }

    /**
     * A failed status check must be caught even if only one datapoint in the window reports
     * it, so that series uses Maximum; utilisation and traffic use Average, which is what
     * the thresholds are expressed in.
     */
    private String statisticFor(String metricName) {
        if (metricName != null && metricName.startsWith("StatusCheckFailed")) {
            return "Maximum";
        }
        return "Average";
    }

    /**
     * Starts the AI pipeline for newly opened incidents. Runs after ingestion has committed
     * so the analysis thread can read the incident it is about to analyse.
     */
    private void triggerAnalysis(List<Long> incidentIds) {
        if (incidentIds.isEmpty() || !aiAnalysisService.autoAnalyzeEnabled()) {
            return;
        }
        for (Long incidentId : incidentIds) {
            aiAnalysisService.analyzeAsync(incidentId);
        }
    }
}
