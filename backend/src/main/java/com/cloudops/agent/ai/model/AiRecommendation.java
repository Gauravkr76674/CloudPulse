package com.cloudops.agent.ai.model;

import java.util.List;

/**
 * RECOMMENDATION stage output.
 *
 * {@code action} is whatever text the model produced. It is deliberately kept as a raw
 * string here: the service layer maps it through
 * {@link com.cloudops.agent.entity.ActionType#parseSafely(String)} and stores anything
 * unrecognised as REJECTED_UNSAFE. Nothing in this record is ever executed directly.
 */
public record AiRecommendation(
        String action,
        String title,
        String rationale,
        String expectedImpact,
        String riskLevel,
        Double confidence,
        Integer priority,
        List<String> alternativeActions) {
}
