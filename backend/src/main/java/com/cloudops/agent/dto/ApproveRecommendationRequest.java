package com.cloudops.agent.dto;

import jakarta.validation.constraints.Size;

/**
 * POST /api/recommendations/{id}/approve body (ADMIN only).
 *
 * dryRun = true runs the whole validation + audit path but never calls AWS. It is the
 * safe way to demo the flow on a live account.
 */
public record ApproveRecommendationRequest(
        Boolean dryRun,
        @Size(max = 500) String note) {
}
