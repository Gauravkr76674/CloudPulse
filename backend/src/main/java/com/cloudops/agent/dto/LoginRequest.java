package com.cloudops.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/auth/login body. */
public record LoginRequest(
        @NotBlank(message = "username is required")
        @Size(max = 60)
        String username,

        @NotBlank(message = "password is required")
        @Size(min = 6, max = 100)
        String password) {
}
