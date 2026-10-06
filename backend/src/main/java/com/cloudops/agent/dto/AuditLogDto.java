package com.cloudops.agent.dto;

import java.time.Instant;

import com.cloudops.agent.entity.AuditOutcome;
import com.cloudops.agent.entity.Role;

/** One audit-trail row. */
public record AuditLogDto(
        Long id,
        String username,
        Role role,
        String action,
        String targetType,
        String targetId,
        AuditOutcome outcome,
        String details,
        String ipAddress,
        Instant createdAt) {
}
