package com.cloudops.agent.dto;

import java.time.Instant;

import com.cloudops.agent.entity.NotificationType;
import com.cloudops.agent.entity.Severity;

/** In-app notification. */
public record NotificationDto(
        Long id,
        NotificationType type,
        Severity severity,
        String title,
        String message,
        Long incidentId,
        boolean read,
        Instant createdAt) {
}
