package com.cloudops.agent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cloudops.agent.dto.MetricDto;
import com.cloudops.agent.dto.MetricSeriesDto;
import com.cloudops.agent.dto.MetricSeriesDto.MetricPointDto;
import com.cloudops.agent.entity.CloudResource;
import com.cloudops.agent.entity.Metric;
import com.cloudops.agent.repository.MetricRepository;
import com.cloudops.agent.util.DtoMapper;

/**
 * Read side of the metric store: chart series and latest values.
 *
 * Writes live in {@link MetricIngestionService}; keeping the two apart means the dashboard
 * can never accidentally trigger detection logic, and every method here is read-only.
 */
@Service
public class MetricService {

    private static final int MAX_WINDOW_MINUTES = 60 * 24 * 14;
    private static final int DEFAULT_WINDOW_MINUTES = 180;

    private final MetricRepository metricRepository;
    private final CloudResourceService resourceService;
    private final AnomalyDetectionService anomalyDetectionService;

    public MetricService(MetricRepository metricRepository,
                        CloudResourceService resourceService,
                        AnomalyDetectionService anomalyDetectionService) {
        this.metricRepository = metricRepository;
        this.resourceService = resourceService;
        this.anomalyDetectionService = anomalyDetectionService;
    }

    /** One chart: every datapoint of one metric on one resource within the window. */
    @Transactional(readOnly = true)
    public MetricSeriesDto series(Long resourceId, String metricName, Integer windowMinutes) {
        CloudResource resource = resourceService.get(resourceId);
        int window = clampWindow(windowMinutes);
        Instant from = Instant.now().minus(window, ChronoUnit.MINUTES);

        List<Metric> metrics = metricRepository
                .findByResourceAndMetricNameAndTimestampAfterOrderByTimestampAsc(
                        resource, metricName, from);

        List<MetricPointDto> points = new ArrayList<>(metrics.size());
        String unit = null;
        for (Metric metric : metrics) {
            points.add(new MetricPointDto(metric.getTimestamp(), metric.getValue(), metric.isAnomalous()));
            if (unit == null) {
                unit = metric.getUnit();
            }
        }

        return new MetricSeriesDto(
                resource.getId(),
                resource.getName(),
                metricName,
                unit,
                anomalyDetectionService.thresholdFor(resource, metricName),
                from,
                Instant.now(),
                points);
    }

    /** Every metric of one resource in the window — the resource detail screen. */
    @Transactional(readOnly = true)
    public List<MetricDto> recent(Long resourceId, Integer windowMinutes) {
        CloudResource resource = resourceService.get(resourceId);
        Instant from = Instant.now().minus(clampWindow(windowMinutes), ChronoUnit.MINUTES);
        return DtoMapper.toMetricDtos(
                metricRepository.findByResourceAndTimestampAfterOrderByTimestampAsc(resource, from));
    }

    /** Latest value of each metric — the resource cards. */
    @Transactional(readOnly = true)
    public List<MetricDto> latest(Long resourceId) {
        CloudResource resource = resourceService.get(resourceId);
        return DtoMapper.toMetricDtos(metricRepository.findLatestPerMetric(resource));
    }

    private int clampWindow(Integer windowMinutes) {
        if (windowMinutes == null || windowMinutes <= 0) {
            return DEFAULT_WINDOW_MINUTES;
        }
        return Math.min(windowMinutes, MAX_WINDOW_MINUTES);
    }
}
