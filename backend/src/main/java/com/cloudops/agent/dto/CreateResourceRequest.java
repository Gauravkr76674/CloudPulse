package com.cloudops.agent.dto;

import com.cloudops.agent.entity.ResourceType;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/resources body - register a resource by hand (OPERATOR or ADMIN). */
public record CreateResourceRequest(
        @NotBlank @Size(max = 120) String resourceId,
        @NotBlank @Size(max = 150) String name,
        ResourceType type,
        @Size(max = 30) String region,
        @Size(max = 40) String instanceType,
        Boolean monitored,
        @DecimalMin("1.0") @DecimalMax("100.0") Double cpuThresholdPercent,
        @DecimalMin("0.0") Double networkInThresholdBytes,
        @Min(1) Integer consecutiveBreachesToOpen) {
}
