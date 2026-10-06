package com.cloudops.agent.entity;

/** How dangerous an action is considered to be. */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH;

    public static RiskLevel parseOrDefault(String raw, RiskLevel fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toUpperCase();
        for (RiskLevel level : values()) {
            if (level.name().equals(normalized)) {
                return level;
            }
        }
        return fallback;
    }
}
