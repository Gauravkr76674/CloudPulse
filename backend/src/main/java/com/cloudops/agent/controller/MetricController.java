package com.cloudops.agent.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cloudops.agent.dto.CollectionResultDto;
import com.cloudops.agent.dto.MetricDto;
import com.cloudops.agent.dto.MetricSeriesDto;
import com.cloudops.agent.service.MetricCollectionService;
import com.cloudops.agent.service.MetricService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * CloudWatch data: what has been collected, plus a manual trigger for a collection sweep.
 *
 * The sweep normally runs on a schedule; the endpoint exists because waiting five minutes
 * during a demo is not practical, and because an operator investigating an incident wants
 * fresh numbers on demand.
 */
@RestController
@RequestMapping("/api/metrics")
@Tag(name = "Metrics", description = "CloudWatch metric history and collection triggers")
public class MetricController {

    private final MetricService metricService;
    private final MetricCollectionService collectionService;

    public MetricController(MetricService metricService, MetricCollectionService collectionService) {
        this.metricService = metricService;
        this.collectionService = collectionService;
    }

    @GetMapping("/resource/{resourceId}")
    @Operation(summary = "All metrics for one resource within the window (default 180 minutes)")
    public List<MetricDto> recent(@PathVariable Long resourceId,
                                 @RequestParam(required = false) Integer windowMinutes) {
        return metricService.recent(resourceId, windowMinutes);
    }

    @GetMapping("/resource/{resourceId}/latest")
    @Operation(summary = "Latest value of each metric for one resource")
    public List<MetricDto> latest(@PathVariable Long resourceId) {
        return metricService.latest(resourceId);
    }

    @GetMapping("/resource/{resourceId}/series")
    @Operation(summary = "One metric as a chart series, with the threshold that applies to it")
    public MetricSeriesDto series(@PathVariable Long resourceId,
                                 @RequestParam(defaultValue = "CPUUtilization") String metricName,
                                 @RequestParam(required = false) Integer windowMinutes) {
        return metricService.series(resourceId, metricName, windowMinutes);
    }

    @PostMapping("/collect")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Poll every monitored resource now and run anomaly detection")
    public CollectionResultDto collectAll() {
        return collectionService.collectAll();
    }

    @PostMapping("/collect/{resourceId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Poll one resource now")
    public CollectionResultDto collectOne(@PathVariable Long resourceId) {
        return collectionService.collectResource(resourceId);
    }
}
