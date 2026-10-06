package com.cloudops.agent.entity;

/** Incident severity, also used for notifications. */
public enum Severity {
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4);

    private final int weight;

    Severity(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }

    public boolean isAtLeast(Severity other) {
        return this.weight >= other.weight;
    }
}
