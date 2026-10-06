package com.cloudops.agent.ai.model;

import java.util.List;

/**
 * Everything the AI is told about an incident. Assembled by the service layer from the
 * database — the model never queries anything itself and never receives credentials.
 *
 * @param allowedActions the safe action catalog; the model must choose from this list,
 *                       which is what keeps recommendations inside the whitelist
 */
public record IncidentContext(
        Long incidentId,
        String title,
        String severity,
        String status,
        String ruleName,
        String metricName,
        Double triggerValue,
        Double thresholdValue,
        Integer occurrenceCount,
        String openedAt,
        String resourceName,
        String resourceId,
        String resourceType,
        String instanceType,
        String region,
        String availabilityZone,
        String resourceStatus,
        String resourceTags,
        List<String> recentMetricLines,
        List<String> priorActionLines,
        List<String> allowedActions) {
}
