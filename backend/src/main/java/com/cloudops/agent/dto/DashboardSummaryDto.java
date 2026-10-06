package com.cloudops.agent.dto;

import java.util.List;
import java.util.Map;

/** GET /api/dashboard/summary - one call that fills the whole landing page. */
public record DashboardSummaryDto(
        ResourceStats resources,
        IncidentStats incidents,
        AiStats ai,
        ActionStats actions,
        Map<String, Long> incidentsBySeverity,
        List<IncidentDto> recentIncidents,
        List<ActionExecutionDto> recentActions,
        List<RecommendationDto> pendingApprovals,
        SystemStatusDto system) {

    public record ResourceStats(long total, long monitored, long running, long stopped) {
    }

    public record IncidentStats(
            long active,
            long open,
            long analyzing,
            long awaitingApproval,
            long remediating,
            long resolved,
            long openedLast24h,
            long resolvedLast24h,
            Double meanTimeToResolveSeconds) {
    }

    public record AiStats(long analyses, long failedAnalyses, long recommendationsProposed,
                          long recommendationsRejectedUnsafe) {
    }

    public record ActionStats(long pending, long succeeded, long failed, long blocked) {
    }
}
