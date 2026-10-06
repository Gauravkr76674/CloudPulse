package com.cloudops.agent.dto;

import com.cloudops.agent.entity.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** POST /api/users body (ADMIN only). */
public record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 60) String username,
        @NotBlank @Email @Size(max = 150) String email,
        @NotBlank @Size(min = 8, max = 100) String password,
        @Size(max = 120) String fullName,
        @NotNull Role role) {
}
