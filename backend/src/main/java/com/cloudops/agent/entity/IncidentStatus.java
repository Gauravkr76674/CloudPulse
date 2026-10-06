package com.cloudops.agent.entity;

/**
 * Incident lifecycle:
 *
 * OPEN -> ANALYZING -> AWAITING_APPROVAL -> REMEDIATING -> RESOLVED -> CLOSED
 *                                     \-> FAILED (execution failed; stays actionable)
 */
public enum IncidentStatus {
    OPEN,
    ANALYZING,
    AWAITING_APPROVAL,
    REMEDIATING,
    RESOLVED,
    CLOSED,
    FAILED;

    public boolean isTerminal() {
        return this == RESOLVED || this == CLOSED;
    }

    /** An incident is "active" while it still needs attention. */
    public boolean isActive() {
        return !isTerminal();
    }
}
