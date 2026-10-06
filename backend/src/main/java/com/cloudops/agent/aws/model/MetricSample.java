package com.cloudops.agent.aws.model;

import java.time.Instant;

/** One metric datapoint returned by CloudWatch (or the mock provider). */
public record MetricSample(
        String metricName,
        String statistic,
        String namespace,
        Instant timestamp,
        double value,
        String unit) {
}
