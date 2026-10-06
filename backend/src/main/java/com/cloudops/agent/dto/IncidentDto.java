package com.cloudops.agent.dto;

import java.time.Instant;

import com.cloudops.agent.entity.IncidentStatus;
import com.cloudops.agent.entity.Severity;

/** Incident summary used by the list screens and the dashboard. */
public record IncidentDto(
        Long id,
        Long resourceId,
        String resourceName,
        String resourceIdentifier,
        String title,
        IncidentStatus status,
        Severity severity,
        String metricName,
        String ruleName,
        Double triggerValue,
        Double thresholdValue,
        int occurrenceCount,
        Instant openedAt,
        Instant lastSeenAt,
        String acknowledgedBy,
        Instant acknowledgedAt,
        String resolvedBy,
        Instant resolvedAt,
        int analysisCount,
        int recommendationCount,
        int pendingRecommendationCount,
        int actionCount) {
}
