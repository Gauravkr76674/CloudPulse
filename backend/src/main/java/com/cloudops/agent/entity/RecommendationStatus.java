package com.cloudops.agent.entity;

/**
 * Recommendation lifecycle.
 *
 * REJECTED_UNSAFE is set by the system (not a human) when the AI proposed an action
 * that is not in the {@link ActionType} whitelist, or proposed one for the wrong
 * resource type. Those rows are kept on purpose: they are the audit evidence that
 * the guardrail fired.
 */
public enum RecommendationStatus {
    PROPOSED,
    APPROVED,
    REJECTED,
    REJECTED_UNSAFE,
    EXECUTED,
    FAILED,
    EXPIRED
}
