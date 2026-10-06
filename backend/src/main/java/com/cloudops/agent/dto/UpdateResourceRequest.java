package com.cloudops.agent.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * PATCH /api/resources/{id} body. Every field is optional: null means "leave as is".
 */
public record UpdateResourceRequest(
        @Size(max = 150) String name,
        Boolean monitored,
        @DecimalMin("1.0") @DecimalMax("100.0") Double cpuThresholdPercent,
        @DecimalMin("0.0") Double networkInThresholdBytes,
        @Min(1) Integer consecutiveBreachesToOpen) {
}
