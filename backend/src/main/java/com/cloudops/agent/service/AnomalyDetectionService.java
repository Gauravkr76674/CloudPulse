package com.cloudops.agent.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cloudops.agent.config.DetectionProperties;
import com.cloudops.agent.entity.CloudResource;
import com.cloudops.agent.entity.Metric;
import com.cloudops.agent.entity.Severity;
import com.cloudops.agent.repository.MetricRepository;

/**
 * Decides whether a freshly collected datapoint is a problem.
 *
 * Two independent rules, no ML library and nothing that needs training data:
 *
 * <ol>
 *   <li><b>THRESHOLD_BREACH</b> — the value is past a hard limit (per-resource override,
 *       otherwise the {@code cloudops.detection.*} default) and has been for
 *       {@code consecutiveBreachesToOpen} datapoints in a row. The "in a row" part is what
 *       stops a single 5-minute spike from paging anyone.</li>
 *   <li><b>BASELINE_DEVIATION</b> — the value is far outside this resource's own recent
 *       behaviour (rolling mean plus z-score over the last {@code baselineWindowSize}
 *       samples). This catches a jump from 4% to 55% CPU, which is clearly abnormal for that
 *       instance even though it never crosses the 90% line.</li>
 * </ol>
 *
 * The verdict also carries the statistics that produced it, and those are written onto the
 * {@link Metric} row so the dashboard can show <em>why</em> a point was flagged instead of
 * asking the operator to trust it.
 */
@Service
public class AnomalyDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionService.class);

    public static final String RULE_THRESHOLD = "THRESHOLD_BREACH";
    public static final String RULE_BASELINE = "BASELINE_DEVIATION";

    /** CloudWatch reports a failed status check as 1.0, so anything above zero is a breach. */
    private static final double STATUS_CHECK_THRESHOLD = 0.0;

    private final MetricRepository metricRepository;
    private final DetectionProperties detectionProperties;

    public AnomalyDetectionService(MetricRepository metricRepository,
                                   DetectionProperties detectionProperties) {
        this.metricRepository = metricRepository;
        this.detectionProperties = detectionProperties;
    }

    /**
     * The outcome of evaluating one datapoint.
     *
     * @param anomalous          this datapoint is abnormal
     * @param shouldOpenIncident enough evidence to raise an incident (not just flag a point)
     * @param consecutiveBreaches how many datapoints in a row are past the threshold, including this one
     */
    public record AnomalyVerdict(
            boolean anomalous,
            boolean shouldOpenIncident,
            String ruleName,
            Double thresholdValue,
            Double baselineMean,
            Double baselineStdDev,
            Double zScore,
            int consecutiveBreaches,
            Severity severity,
            String reason) {

        static AnomalyVerdict normal(Double threshold, Double mean, Double stdDev, Double zScore) {
            return new AnomalyVerdict(false, false, null, threshold, mean, stdDev, zScore, 0,
                    Severity.LOW, "Within expected range");
        }
    }

    /**
     * Evaluates {@code metric} against both rules and stamps the resulting statistics onto
     * it. The metric must already be populated with resource, name, statistic and value;
     * it does not need to be saved yet.
     *
     * <p>Call this <em>after</em> the earlier datapoints of the same batch have been saved,
     * so the consecutive-breach count sees them.
     */
    @Transactional(readOnly = true)
    public AnomalyVerdict evaluate(CloudResource resource, Metric metric) {
        double value = metric.getValue() == null ? 0.0 : metric.getValue();
        Double threshold = thresholdFor(resource, metric.getMetricName());

        List<Metric> history = recentHistory(resource, metric.getMetricName(), metric.getStatistic());
        Stats stats = summarise(history);

        Double zScore = stats.zScore(value);
        AnomalyVerdict verdict = decide(resource, metric, value, threshold, stats, zScore);

        // Persisted alongside the datapoint so the UI can justify the flag
        metric.setThresholdValue(threshold);
        metric.setBaselineMean(stats.usable() ? stats.mean() : null);
        metric.setBaselineStdDev(stats.usable() ? stats.stdDev() : null);
        metric.setZScore(zScore);
        metric.setAnomalous(verdict.anomalous());

        if (verdict.anomalous()) {
            log.debug("{} {} = {} flagged by {} ({})", resource.getResourceId(),
                    metric.getMetricName(), value, verdict.ruleName(), verdict.reason());
        }
        return verdict;
    }

    private AnomalyVerdict decide(CloudResource resource, Metric metric, double value,
                                  Double threshold, Stats stats, Double zScore) {
        int required = requiredBreaches(resource);

        // --- rule 1: hard threshold, sustained ---
        if (threshold != null && value > threshold) {
            int consecutive = 1 + countConsecutiveAbove(resource, metric.getMetricName(),
                    metric.getStatistic(), threshold, metric.getTimestamp());
            Severity severity = severityForBreach(value, threshold, consecutive, metric.getMetricName());
            boolean open = consecutive >= required;
            String reason = String.format(
                    "%s at %.2f is above the %.2f threshold for %d consecutive datapoint(s); %d required to open an incident.",
                    metric.getMetricName(), value, threshold, consecutive, required);
            return new AnomalyVerdict(true, open, RULE_THRESHOLD, threshold,
                    stats.usable() ? stats.mean() : null,
                    stats.usable() ? stats.stdDev() : null,
                    zScore, consecutive, severity, reason);
        }

        // --- rule 2: statistical deviation from this resource's own baseline ---
        if (detectionProperties.isBaselineEnabled()
                && stats.usable()
                && zScore != null
                && zScore >= detectionProperties.getBaselineZThreshold()
                && value > stats.mean()) {
            String reason = String.format(
                    "%s at %.2f is %.1f standard deviations above its own %d-sample baseline (mean %.2f).",
                    metric.getMetricName(), value, zScore, stats.count(), stats.mean());
            // A statistical outlier below the hard threshold is worth one incident, not a page
            return new AnomalyVerdict(true, true, RULE_BASELINE, threshold, stats.mean(),
                    stats.stdDev(), zScore, 1, Severity.MEDIUM, reason);
        }

        return AnomalyVerdict.normal(threshold,
                stats.usable() ? stats.mean() : null,
                stats.usable() ? stats.stdDev() : null,
                zScore);
    }

    // ---------------------------------------------------------------------
    // recovery — the other half of the loop
    // ---------------------------------------------------------------------

    /**
     * How many of the most recent datapoints, newest first, are back at or below the
     * threshold. Used to auto-resolve an incident once a remediation has actually worked.
     */
    @Transactional(readOnly = true)
    public int countConsecutiveBelow(CloudResource resource, String metricName, String statistic,
                                     double threshold) {
        List<Metric> history = recentHistory(resource, metricName, statistic);
        int healthy = 0;
        for (Metric metric : history) {
            double value = metric.getValue() == null ? 0.0 : metric.getValue();
            if (value > threshold) {
                break;
            }
            healthy++;
        }
        return healthy;
    }

    /** The threshold in force for a resource + metric, or null when the metric has none. */
    public Double thresholdFor(CloudResource resource, String metricName) {
        if (metricName == null) {
            return null;
        }
        return switch (metricName) {
            case "CPUUtilization" -> resource.getCpuThresholdPercent() != null
                    ? resource.getCpuThresholdPercent()
                    : detectionProperties.getDefaultCpuThresholdPercent();
            case "NetworkIn" -> resource.getNetworkInThresholdBytes() != null
                    ? resource.getNetworkInThresholdBytes()
                    : detectionProperties.getDefaultNetworkInThresholdBytes();
            case "StatusCheckFailed", "StatusCheckFailed_Instance", "StatusCheckFailed_System" ->
                    STATUS_CHECK_THRESHOLD;
            // NetworkOut and anything else are collected for context and baseline only
            default -> null;
        };
    }

    public int requiredBreaches(CloudResource resource) {
        Integer override = resource.getConsecutiveBreachesToOpen();
        int required = override != null ? override : detectionProperties.getConsecutiveBreachesToOpen();
        return Math.max(1, required);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /** Newest-first window used for both the baseline and the consecutive counters. */
    private List<Metric> recentHistory(CloudResource resource, String metricName, String statistic) {
        int window = Math.max(2, detectionProperties.getBaselineWindowSize());
        return metricRepository.findByResourceAndMetricNameAndStatisticOrderByTimestampDesc(
                resource, metricName, statistic, PageRequest.of(0, window));
    }

    /**
     * Counts stored datapoints strictly older than {@code before} that are above the
     * threshold, stopping at the first healthy one.
     */
    private int countConsecutiveAbove(CloudResource resource, String metricName, String statistic,
                                      double threshold, Instant before) {
        List<Metric> history = recentHistory(resource, metricName, statistic);
        int consecutive = 0;
        for (Metric metric : history) {
            if (before != null && metric.getTimestamp() != null
                    && !metric.getTimestamp().isBefore(before)) {
                // Ignore the current point and anything newer (re-polled windows overlap)
                continue;
            }
            double value = metric.getValue() == null ? 0.0 : metric.getValue();
            if (value <= threshold) {
                break;
            }
            consecutive++;
        }
        return consecutive;
    }

    /**
     * Severity grows with how far past the threshold the value is and how long it has
     * been there. A failed status check is critical immediately — the instance is down.
     */
    private Severity severityForBreach(double value, double threshold, int consecutive,
                                       String metricName) {
        if (metricName != null && metricName.startsWith("StatusCheckFailed")) {
            return Severity.CRITICAL;
        }
        double exceedance = threshold > 0 ? ((value - threshold) / threshold) * 100.0 : 100.0;
        if (exceedance >= 25.0 || consecutive >= 4) {
            return Severity.CRITICAL;
        }
        if (exceedance >= 10.0 || consecutive >= 3) {
            return Severity.HIGH;
        }
        return Severity.MEDIUM;
    }

    /** Rolling mean and population standard deviation over the recent window. */
    private Stats summarise(List<Metric> history) {
        int count = 0;
        double sum = 0.0;
        for (Metric metric : history) {
            if (metric.getValue() != null) {
                sum += metric.getValue();
                count++;
            }
        }
        if (count == 0) {
            return new Stats(0, 0.0, 0.0, detectionProperties.getBaselineMinSamples());
        }

        double mean = sum / count;
        double squaredDeviations = 0.0;
        for (Metric metric : history) {
            if (metric.getValue() != null) {
                double delta = metric.getValue() - mean;
                squaredDeviations += delta * delta;
            }
        }
        double stdDev = Math.sqrt(squaredDeviations / count);
        return new Stats(count, mean, stdDev, detectionProperties.getBaselineMinSamples());
    }

    /** Baseline statistics for one metric of one resource. */
    private record Stats(int count, double mean, double stdDev, int minSamples) {

        boolean usable() {
            return count >= minSamples;
        }

        /** Null when there is not enough history, or when the series is perfectly flat. */
        Double zScore(double value) {
            if (!usable() || stdDev <= 0.0001) {
                return null;
            }
            return (value - mean) / stdDev;
        }
    }
}
