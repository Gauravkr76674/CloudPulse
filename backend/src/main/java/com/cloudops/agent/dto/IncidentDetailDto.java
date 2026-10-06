package com.cloudops.agent.dto;

import java.util.List;

/** Everything the incident detail screen needs in one round trip. */
public record IncidentDetailDto(
        IncidentDto incident,
        String description,
        String resolutionNote,
        List<AiAnalysisDto> analyses,
        List<RecommendationDto> recommendations,
        List<ActionExecutionDto> actions,
        List<MetricDto> recentMetrics) {
}
