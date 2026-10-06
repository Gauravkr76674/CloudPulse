package com.cloudops.agent.entity;

/**
 * Execution lifecycle of a remediation action.
 * BLOCKED means a guardrail (kill switch, rate limit, IAM preflight, wrong resource
 * type) stopped the action before any AWS call was attempted.
 */
public enum ActionStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    BLOCKED,
    CANCELLED
}
