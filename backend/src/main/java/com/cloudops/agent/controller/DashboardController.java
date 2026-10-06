package com.cloudops.agent.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cloudops.agent.dto.DashboardSummaryDto;
import com.cloudops.agent.service.DashboardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Everything the landing page needs, in one call. */
@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "Dashboard", description = "Aggregated counters for the landing page")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @Operation(summary = "Resource, incident, AI and action counters plus recent activity")
    public DashboardSummaryDto summary() {
        return dashboardService.summary();
    }

    @GetMapping("/severity")
    @Operation(summary = "Active incidents grouped by severity, for the chart's own refresh")
    public Map<String, Long> bySeverity() {
        return dashboardService.activeIncidentsBySeverity();
    }
}
