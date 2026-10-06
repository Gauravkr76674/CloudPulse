package com.cloudops.agent.dto;

import java.time.Instant;

import com.cloudops.agent.entity.ResourceSource;
import com.cloudops.agent.entity.ResourceStatus;
import com.cloudops.agent.entity.ResourceType;

/** A managed cloud resource as exposed by the API. */
public record CloudResourceDto(
        Long id,
        String resourceId,
        String name,
        ResourceType type,
        String region,
        String availabilityZone,
        String instanceType,
        ResourceStatus status,
        boolean monitored,
        ResourceSource source,
        String tagsJson,
        String privateIp,
        String publicIp,
        Instant launchTime,
        Double cpuThresholdPercent,
        Double networkInThresholdBytes,
        Integer consecutiveBreachesToOpen,
        Instant lastCheckedAt,
        String createdBy,
        Instant createdAt,
        long activeIncidents) {
}
