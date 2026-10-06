package com.cloudops.agent.dto;

import java.time.Instant;

import com.cloudops.agent.entity.ActionType;
import com.cloudops.agent.entity.RecommendationStatus;
import com.cloudops.agent.entity.RiskLevel;

/**
 * A proposed action. {@code actionType} is null exactly when the model suggested
 * something outside the safe catalog - in that case {@code aiSuggestedAction} holds
 * the raw text and {@code status} is REJECTED_UNSAFE.
 */
public record RecommendationDto(
        Long id,
        Long incidentId,
        ActionType actionType,
        String actionDisplayName,
        String aiSuggestedAction,
        String title,
        String rationale,
        String expectedImpact,
        RiskLevel riskLevel,
        Double confidence,
        int priority,
        RecommendationStatus status,
        boolean requiresApproval,
        boolean mutating,
        String rejectionReason,
        String decidedBy,
        Instant decidedAt,
        String decisionNote,
        Instant createdAt) {
}
