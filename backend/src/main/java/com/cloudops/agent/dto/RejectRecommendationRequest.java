package com.cloudops.agent.dto;

import jakarta.validation.constraints.Size;

/** POST /api/recommendations/{id}/reject body (ADMIN only). */
public record RejectRecommendationRequest(@Size(max = 500) String note) {
}
