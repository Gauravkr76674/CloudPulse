package com.cloudops.agent.dto;

import java.time.Instant;

import com.cloudops.agent.entity.Role;

/** A user as exposed by the API. The password hash is deliberately absent. */
public record UserDto(
        Long id,
        String username,
        String email,
        String fullName,
        Role role,
        boolean enabled,
        Instant createdAt,
        Instant lastLoginAt) {
}
