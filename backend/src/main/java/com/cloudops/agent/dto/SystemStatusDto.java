package com.cloudops.agent.dto;

/**
 * GET /api/system/status - lets the dashboard show, at a glance, whether it is
 * talking to real AWS or the mock provider, and whether the AI layer is configured.
 */
public record SystemStatusDto(
        String cloudProvider,
        String region,
        boolean cloudReachable,
        String cloudMessage,
        String callerIdentity,
        String aiProvider,
        String aiDefaultModel,
        boolean aiConfigured,
        boolean monitoringEnabled,
        long pollIntervalMs,
        boolean remediationEnabled,
        boolean forceDryRun,
        boolean approvalRequired) {
}
