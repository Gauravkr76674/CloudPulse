package com.cloudops.agent.ai.model;

import com.cloudops.agent.entity.Severity;

/** DIAGNOSIS stage output: the probable root cause and the reasoning behind it. */
public record AiDiagnosis(
        String summary,
        String rootCause,
        String reasoning,
        Severity suggestedSeverity,
        Double confidence) {
}
