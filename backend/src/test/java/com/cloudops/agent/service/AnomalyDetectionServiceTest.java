package com.cloudops.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import com.cloudops.agent.config.DetectionProperties;
import com.cloudops.agent.entity.CloudResource;
import com.cloudops.agent.entity.Metric;
import com.cloudops.agent.entity.ResourceStatus;
import com.cloudops.agent.entity.ResourceType;
import com.cloudops.agent.entity.Severity;
import com.cloudops.agent.repository.MetricRepository;
import com.cloudops.agent.service.AnomalyDetectionService.AnomalyVerdict;

/**
 * Detection rules, driven with synthetic history.
 *
 * The interesting cases are the ones that must NOT open an incident: a single spike, and a
 * value that is high in absolute terms but normal for that particular instance. Those are
 * what make the difference between a useful agent and a pager that everybody mutes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnomalyDetectionServiceTest {

    private static final String CPU = "CPUUtilization";
    private static final String AVG = "Average";

    @Mock private MetricRepository metricRepository;

    private DetectionProperties properties;
    private AnomalyDetectionService service;
    private CloudResource resource;
    private Instant now;

    @BeforeEach
    void setUp() {
        properties = new DetectionProperties();
        service = new AnomalyDetectionService(metricRepository, properties);
        now = Instant.parse("2026-08-25T10:00:00Z");

        resource = new CloudResource();
        resource.setId(1L);
        resource.setResourceId("i-0abc");
        resource.setName("demo-web-01");
        resource.setType(ResourceType.EC2_INSTANCE);
        resource.setStatus(ResourceStatus.RUNNING);
    }

    /** Newest-first history, exactly as the repository returns it. */
    private void givenHistory(String metricName, double... valuesNewestFirst) {
        List<Metric> history = new ArrayList<>();
        for (int i = 0; i < valuesNewestFirst.length; i++) {
            Metric metric = new Metric();
            metric.setResource(resource);
            metric.setMetricName(metricName);
            metric.setStatistic(AVG);
            metric.setValue(valuesNewestFirst[i]);
            // strictly older than the datapoint under evaluation, 5 minutes apart
            metric.setTimestamp(now.minus(5L * (i + 1), ChronoUnit.MINUTES));
            history.add(metric);
        }
        when(metricRepository.findByResourceAndMetricNameAndStatisticOrderByTimestampDesc(
                any(CloudResource.class), eq(metricName), anyString(), any(Pageable.class)))
                .thenReturn(history);
    }

    private Metric datapoint(String metricName, double value) {
        Metric metric = new Metric();
        metric.setResource(resource);
        metric.setMetricName(metricName);
        metric.setStatistic(AVG);
        metric.setValue(value);
        metric.setTimestamp(now);
        return metric;
    }

    // ------------------------------------------------------------------
    // rule 1 - sustained threshold breach
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a healthy datapoint is not flagged")
    void healthyValueIsNormal() {
        givenHistory(CPU, 12.0, 14.0, 11.0, 13.0, 12.5, 12.0, 13.5, 11.5, 12.0);
        Metric metric = datapoint(CPU, 13.0);

        AnomalyVerdict verdict = service.evaluate(resource, metric);

        assertThat(verdict.anomalous()).isFalse();
        assertThat(verdict.shouldOpenIncident()).isFalse();
        assertThat(verdict.ruleName()).isNull();
        assertThat(metric.isAnomalous()).isFalse();
        assertThat(metric.getThresholdValue()).isEqualTo(90.0);
    }

    @Test
    @DisplayName("a single spike is flagged but does NOT open an incident")
    void singleSpikeDoesNotOpenIncident() {
        // most recent stored point is healthy, so this is the first breach
        givenHistory(CPU, 20.0, 22.0, 19.0);
        Metric metric = datapoint(CPU, 97.0);

        AnomalyVerdict verdict = service.evaluate(resource, metric);

        assertThat(verdict.anomalous()).isTrue();
        assertThat(verdict.shouldOpenIncident()).isFalse();
        assertThat(verdict.ruleName()).isEqualTo(AnomalyDetectionService.RULE_THRESHOLD);
        assertThat(verdict.consecutiveBreaches()).isEqualTo(1);
        assertThat(verdict.reason()).contains("2 required to open an incident");
    }

    @Test
    @DisplayName("two consecutive breaches open an incident")
    void sustainedBreachOpensIncident() {
        givenHistory(CPU, 95.0, 20.0, 19.0);
        Metric metric = datapoint(CPU, 97.0);

        AnomalyVerdict verdict = service.evaluate(resource, metric);

        assertThat(verdict.anomalous()).isTrue();
        assertThat(verdict.shouldOpenIncident()).isTrue();
        assertThat(verdict.consecutiveBreaches()).isEqualTo(2);
        // 97 is only 7.8% past 90 and this is the 2nd breach -> MEDIUM
        assertThat(verdict.severity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    @DisplayName("severity escalates with how far past the threshold the value is")
    void severityEscalatesWithExceedance() {
        givenHistory(CPU, 96.0, 20.0);
        AnomalyVerdict high = service.evaluate(resource, datapoint(CPU, 100.0));
        assertThat(high.severity()).isEqualTo(Severity.HIGH);

        givenHistory(CPU, 96.0, 95.0, 94.0, 93.0);
        AnomalyVerdict critical = service.evaluate(resource, datapoint(CPU, 99.0));
        assertThat(critical.consecutiveBreaches()).isEqualTo(5);
        assertThat(critical.severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    @DisplayName("a per-resource threshold overrides the global default")
    void perResourceThresholdWins() {
        resource.setCpuThresholdPercent(50.0);
        givenHistory(CPU, 55.0, 10.0);

        AnomalyVerdict verdict = service.evaluate(resource, datapoint(CPU, 60.0));

        assertThat(verdict.thresholdValue()).isEqualTo(50.0);
        assertThat(verdict.shouldOpenIncident()).isTrue();
    }

    @Test
    @DisplayName("a failed status check is critical immediately")
    void failedStatusCheckIsCritical() {
        givenHistory("StatusCheckFailed", 0.0, 0.0);

        AnomalyVerdict verdict = service.evaluate(resource, datapoint("StatusCheckFailed", 1.0));

        assertThat(verdict.anomalous()).isTrue();
        assertThat(verdict.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(verdict.thresholdValue()).isEqualTo(0.0);
    }

    // ------------------------------------------------------------------
    // rule 2 - baseline deviation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a jump far outside the resource's own baseline opens an incident below the threshold")
    void baselineDeviationIsCaught() {
        // this instance normally idles around 4%; 55% is nowhere near 90 but clearly abnormal
        givenHistory(CPU, 4.0, 4.2, 3.8, 4.1, 3.9, 4.0, 4.3, 3.7, 4.0, 4.1);

        AnomalyVerdict verdict = service.evaluate(resource, datapoint(CPU, 55.0));

        assertThat(verdict.anomalous()).isTrue();
        assertThat(verdict.shouldOpenIncident()).isTrue();
        assertThat(verdict.ruleName()).isEqualTo(AnomalyDetectionService.RULE_BASELINE);
        assertThat(verdict.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(verdict.zScore()).isNotNull();
        assertThat(verdict.zScore()).isGreaterThan(3.0);
        assertThat(verdict.reason()).contains("standard deviations above its own");
    }

    @Test
    @DisplayName("too little history means no baseline verdict at all")
    void shortHistoryProducesNoBaselineVerdict() {
        givenHistory(CPU, 4.0, 4.2, 3.8);   // below the 8-sample minimum

        AnomalyVerdict verdict = service.evaluate(resource, datapoint(CPU, 55.0));

        assertThat(verdict.anomalous()).isFalse();
        assertThat(verdict.baselineMean()).isNull();
        assertThat(verdict.zScore()).isNull();
    }

    @Test
    @DisplayName("a perfectly flat series produces no z-score instead of infinity")
    void flatSeriesHasNoZScore() {
        givenHistory(CPU, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0);

        AnomalyVerdict verdict = service.evaluate(resource, datapoint(CPU, 5.0));

        assertThat(verdict.zScore()).isNull();
        assertThat(verdict.anomalous()).isFalse();
    }

    @Test
    @DisplayName("a dip below the baseline is never an anomaly")
    void lowOutlierIsNotAnAnomaly() {
        givenHistory(CPU, 60.0, 61.0, 59.0, 60.5, 60.0, 59.5, 60.2, 60.8, 60.0);

        AnomalyVerdict verdict = service.evaluate(resource, datapoint(CPU, 1.0));

        assertThat(verdict.anomalous()).isFalse();
    }

    @Test
    @DisplayName("baseline detection can be switched off")
    void baselineCanBeDisabled() {
        properties.setBaselineEnabled(false);
        givenHistory(CPU, 4.0, 4.2, 3.8, 4.1, 3.9, 4.0, 4.3, 3.7, 4.0, 4.1);

        assertThat(service.evaluate(resource, datapoint(CPU, 55.0)).anomalous()).isFalse();
    }

    // ------------------------------------------------------------------
    // thresholds, recovery counting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("only metrics with a meaningful limit get a threshold")
    void thresholdsAreMetricSpecific() {
        assertThat(service.thresholdFor(resource, CPU)).isEqualTo(90.0);
        assertThat(service.thresholdFor(resource, "NetworkIn")).isEqualTo(50_000_000.0);
        assertThat(service.thresholdFor(resource, "StatusCheckFailed_System")).isEqualTo(0.0);
        assertThat(service.thresholdFor(resource, "NetworkOut")).isNull();
        assertThat(service.thresholdFor(resource, "SomethingElse")).isNull();
        assertThat(service.thresholdFor(resource, null)).isNull();
    }

    @Test
    @DisplayName("recovery counting stops at the first datapoint still above the threshold")
    void recoveryCountStopsAtFirstBreach() {
        givenHistory(CPU, 20.0, 22.0, 25.0, 95.0, 97.0);

        assertThat(service.countConsecutiveBelow(resource, CPU, AVG, 90.0)).isEqualTo(3);
    }

    @Test
    @DisplayName("required breaches honours the per-resource override and never drops below 1")
    void requiredBreachesRespectsOverride() {
        assertThat(service.requiredBreaches(resource)).isEqualTo(2);

        resource.setConsecutiveBreachesToOpen(4);
        assertThat(service.requiredBreaches(resource)).isEqualTo(4);

        resource.setConsecutiveBreachesToOpen(0);
        assertThat(service.requiredBreaches(resource)).isEqualTo(1);
    }
}
