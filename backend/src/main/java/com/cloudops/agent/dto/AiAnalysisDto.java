package com.cloudops.agent.dto;

import java.time.Instant;

import com.cloudops.agent.entity.AiStage;
import com.cloudops.agent.entity.Severity;

/** One AI pipeline stage result. rawResponse is included so the UI can show the model output verbatim. */
public record AiAnalysisDto(
        Long id,
        Long incidentId,
        AiStage stage,
        String provider,
        String modelName,
        String summary,
        String rootCause,
        String reasoning,
        Double confidence,
        Severity suggestedSeverity,
        Long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        boolean successful,
        String errorMessage,
        String rawResponse,
        Instant createdAt) {
}
