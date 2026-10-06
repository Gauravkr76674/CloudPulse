package com.cloudops.agent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cloudops.agent.aws.model.MetricSample;
import com.cloudops.agent.entity.CloudResource;
import com.cloudops.agent.entity.Metric;
import com.cloudops.agent.exception.NotFoundException;
import com.cloudops.agent.repository.CloudResourceRepository;
import com.cloudops.agent.repository.MetricRepository;
import com.cloudops.agent.service.AnomalyDetectionService.AnomalyVerdict;
import com.cloudops.agent.service.IncidentService.BreachResult;

/**
 * The database half of metric collection: stores datapoints, runs detection over them and
 * feeds the results into the incident tracker.
 *
 * Kept separate from {@link MetricCollectionService} on purpose. That class talks to
 * CloudWatch, which is slow and can hang; this one owns the transaction. Mixing them would
 * hold a database transaction open for the duration of a network call — the classic way to
 * exhaust a connection pool.
 *
 * Datapoints are processed oldest-first and flushed one at a time, because the
 * consecutive-breach rule needs to see the earlier points of the same batch.
 */
@Service
public class MetricIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MetricIngestionService.class);

    private final MetricRepository metricRepository;
    private final CloudResourceRepository resourceRepository;
    private final AnomalyDetectionService anomalyDetectionService;
    private final IncidentService incidentService;

    public MetricIngestionService(MetricRepository metricRepository,
                                 CloudResourceRepository resourceRepository,
                                 AnomalyDetectionService anomalyDetectionService,
                                 IncidentService incidentService) {
        this.metricRepository = metricRepository;
        this.resourceRepository = resourceRepository;
        this.anomalyDetectionService = anomalyDetectionService;
        this.incidentService = incidentService;
    }

    /**
     * What one batch of datapoints did.
     *
     * @param openedIncidentIds incidents opened by this batch — the caller uses these to
     *                          kick off AI analysis once the transaction has committed
     */
    public record IngestionOutcome(
            int stored,
            int duplicates,
            int anomalies,
            List<Long> openedIncidentIds,
            int incidentsUpdated,
            int incidentsResolved) {

        public static IngestionOutcome empty() {
            return new IngestionOutcome(0, 0, 0, List.of(), 0, 0);
        }

        public int incidentsOpened() {
            return openedIncidentIds.size();
        }
    }

    /**
     * Stores a batch of samples for one resource and runs the detection pipeline over them.
     *
     * @param resourceId the local database id, not the AWS instance id
     * @param samples    datapoints in any order; duplicates of stored points are ignored
     */
    @Transactional
    public IngestionOutcome ingest(Long resourceId, List<MetricSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return IngestionOutcome.empty();
        }

        CloudResource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> NotFoundException.of("CloudResource", resourceId));

        List<MetricSample> ordered = new ArrayList<>(samples);
        ordered.sort(Comparator.comparing(MetricSample::timestamp,
                Comparator.nullsFirst(Comparator.naturalOrder())));

        int stored = 0;
        int duplicates = 0;
        int anomalies = 0;
        int updated = 0;
        List<Long> opened = new ArrayList<>();

        // Tracks, per metric+statistic, whether the newest point of this batch was a breach.
        // Only series that end healthy are candidates for auto-resolution.
        Map<String, Boolean> seriesEndedHealthy = new LinkedHashMap<>();

        for (MetricSample sample : ordered) {
            if (sample.timestamp() == null || sample.metricName() == null) {
                continue;
            }
            String key = sample.metricName() + "|" + sample.statistic();

            if (metricRepository.existsByResourceAndMetricNameAndStatisticAndTimestamp(
                    resource, sample.metricName(), sample.statistic(), sample.timestamp())) {
                // CloudWatch windows overlap between polls; this is expected, not an error
                duplicates++;
                continue;
            }

            Metric metric = new Metric();
            metric.setResource(resource);
            metric.setMetricName(sample.metricName());
            metric.setNamespace(sample.namespace());
            metric.setStatistic(sample.statistic());
            metric.setValue(sample.value());
            metric.setUnit(sample.unit());
            metric.setTimestamp(sample.timestamp());
            metric.setCollectedAt(Instant.now());

            // evaluate() stamps threshold/baseline/z-score onto the metric before it is saved
            AnomalyVerdict verdict = anomalyDetectionService.evaluate(resource, metric);
            metricRepository.saveAndFlush(metric);
            stored++;

            if (verdict.anomalous()) {
                anomalies++;
            }
            seriesEndedHealthy.put(key, !verdict.anomalous());

            if (verdict.shouldOpenIncident()) {
                BreachResult result = incidentService.registerBreach(resource, metric, verdict);
                if (result.opened()) {
                    opened.add(result.incident().getId());
                } else {
                    updated++;
                }
            }
        }

        int resolved = 0;
        for (Map.Entry<String, Boolean> entry : seriesEndedHealthy.entrySet()) {
            if (!entry.getValue()) {
                continue;
            }
            String[] parts = entry.getKey().split("\\|", 2);
            String metricName = parts[0];
            String statistic = parts.length > 1 ? parts[1] : null;
            resolved += incidentService.checkRecovery(resource, metricName, statistic);
        }

        if (stored > 0 || resolved > 0) {
            log.debug("{}: stored {} datapoint(s), {} duplicate(s), {} anomaly(ies), "
                            + "{} incident(s) opened, {} updated, {} resolved",
                    resource.getResourceId(), stored, duplicates, anomalies,
                    opened.size(), updated, resolved);
        }
        return new IngestionOutcome(stored, duplicates, anomalies, opened, updated, resolved);
    }

    /** Retention housekeeping, called from the scheduled purge. */
    @Transactional
    public int deleteOlderThan(Instant cutoff) {
        return metricRepository.deleteOlderThan(cutoff);
    }
}
