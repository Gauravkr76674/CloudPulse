package com.cloudops.agent.aws.model;

import java.time.Instant;
import java.util.Map;

import com.cloudops.agent.entity.ResourceStatus;
import com.cloudops.agent.entity.ResourceType;

/**
 * A resource as reported by a cloud provider, before it is persisted as a
 * {@link com.cloudops.agent.entity.CloudResource}. Keeping the provider's view separate
 * from the entity means the AWS and mock providers share one contract.
 */
public record DiscoveredResource(
        String resourceId,
        String name,
        ResourceType type,
        String region,
        String availabilityZone,
        String instanceType,
        ResourceStatus status,
        Map<String, String> tags,
        String privateIp,
        String publicIp,
        Instant launchTime) {
}
