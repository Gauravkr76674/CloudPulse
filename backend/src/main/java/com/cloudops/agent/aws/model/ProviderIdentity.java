package com.cloudops.agent.aws.model;

/**
 * Who the application is authenticated as, shown on the System page so the operator
 * can confirm which account and region are in play before approving anything.
 */
public record ProviderIdentity(
        String provider,
        boolean credentialsAvailable,
        String accountId,
        String arn,
        String region,
        String message) {

    public static ProviderIdentity unavailable(String provider, String region, String message) {
        return new ProviderIdentity(provider, false, null, null, region, message);
    }
}
