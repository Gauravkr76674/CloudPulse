package com.cloudops.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/auth/change-password body. The current password is required even though the caller
 * already holds a valid token: a stolen token must not be enough to take over the account.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "currentPassword is required")
        @Size(max = 100)
        String currentPassword,

        @NotBlank(message = "newPassword is required")
        @Size(min = 8, max = 100, message = "newPassword must be at least 8 characters")
        String newPassword) {
}
