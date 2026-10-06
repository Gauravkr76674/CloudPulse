package com.cloudops.agent.aws.model;

/**
 * Outcome of a provider mutation (reboot / stop / start / tag).
 * {@code providerRequestId} is the AWS request id, which makes an action traceable in
 * CloudTrail from the audit log.
 */
public record ProviderActionResult(
        boolean success,
        String message,
        String providerRequestId) {

    public static ProviderActionResult ok(String message, String requestId) {
        return new ProviderActionResult(true, message, requestId);
    }

    public static ProviderActionResult failed(String message) {
        return new ProviderActionResult(false, message, null);
    }
}
