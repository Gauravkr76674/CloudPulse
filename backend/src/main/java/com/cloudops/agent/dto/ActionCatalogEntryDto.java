package com.cloudops.agent.dto;

import com.cloudops.agent.entity.ActionType;
import com.cloudops.agent.entity.RiskLevel;

/**
 * GET /api/actions/catalog entry - the whitelist the AI is allowed to choose from,
 * surfaced to the UI so an approver can see exactly what an action will do.
 */
public record ActionCatalogEntryDto(
        ActionType actionType,
        String displayName,
        String description,
        boolean mutating,
        boolean approvalRequired,
        RiskLevel defaultRisk,
        boolean executable,
        String executionNote) {
}
