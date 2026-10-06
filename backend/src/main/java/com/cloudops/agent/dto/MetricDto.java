package com.cloudops.agent.dto;

import java.time.Instant;

/** One stored CloudWatch datapoint. */
public record MetricDto(
        Long id,
        Long resourceId,
        String resourceName,
        String metricName,
        String statistic,
        String namespace,
        Double value,
        String unit,
        Instant timestamp,
        boolean anomalous,
        Double thresholdValue,
        Double baselineMean,
        Double zScore) {
}
