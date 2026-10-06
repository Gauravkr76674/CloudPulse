package com.cloudops.agent.dto;

import com.cloudops.agent.entity.Severity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** POST /api/incidents body - lets an operator raise an incident by hand. */
public record CreateIncidentRequest(
        @NotNull Long resourceId,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 4000) String description,
        Severity severity,
        @Size(max = 60) String metricName) {
}
