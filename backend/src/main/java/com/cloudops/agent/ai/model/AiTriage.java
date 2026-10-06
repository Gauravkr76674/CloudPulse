package com.cloudops.agent.ai.model;

import com.cloudops.agent.entity.Severity;

/**
 * DETECTION stage output: does the signal look like a real problem, and how bad is it?
 */
public record AiTriage(
        boolean genuineAnomaly,
        Severity suggestedSeverity,
        String summary,
        String signalAssessment,
        Double confidence) {
}
