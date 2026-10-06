package com.cloudops.agent.dto;

import java.time.Instant;
import java.util.List;

/** Chart-friendly series for one resource + metric. */
public record MetricSeriesDto(
        Long resourceId,
        String resourceName,
        String metricName,
        String unit,
        Double thresholdValue,
        Instant from,
        Instant to,
        List<MetricPointDto> points) {

    /** A single point on the series. */
    public record MetricPointDto(Instant timestamp, Double value, boolean anomalous) {
    }
}
