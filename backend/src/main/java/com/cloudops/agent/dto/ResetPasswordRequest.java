package com.cloudops.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/users/{id}/reset-password body (ADMIN only). */
public record ResetPasswordRequest(
        @NotBlank(message = "newPassword is required")
        @Size(min = 8, max = 100, message = "newPassword must be at least 8 characters")
        String newPassword) {
}
