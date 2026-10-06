package com.cloudops.agent.dto;

import java.time.Instant;

import com.cloudops.agent.entity.ActionStatus;
import com.cloudops.agent.entity.ActionType;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The record of one remediation attempt, shown on the incident detail and history screens.
 *
 * <p>This DTO opts out of the global non_null inclusion. It is the forensic record of something
 * the agent did to real infrastructure, and the outcome fields carry meaning by being null:
 * {@code "errorMessage": null} positively states that the attempt raised no error, whereas an
 * absent key is ambiguous between "no error" and "this response does not report errors". An
 * auditable record should not change shape depending on its own contents, so every field is
 * always present.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ActionExecutionDto(
        Long id,
        Long incidentId,
        Long recommendationId,
        Long resourceId,
        String resourceName,
        String resourceIdentifier,
        ActionType actionType,
        String actionDisplayName,
        ActionStatus status,
        boolean dryRun,
        String requestedBy,
        String approvedBy,
        String resultMessage,
        String errorMessage,
        String blockedReason,
        String providerRequestId,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        Instant createdAt) {
}
