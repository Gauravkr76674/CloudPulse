package com.cloudops.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds <code>cloudops.cloud.*</code>. Note that AWS credentials are deliberately
 * absent: they are resolved at runtime by the AWS SDK DefaultCredentialsProvider
 * (env vars, ~/.aws/credentials, or an EC2 instance role).
 */
@ConfigurationProperties(prefix = "cloudops.cloud")
public class CloudProperties {

    /** "mock" for offline development, "aws" for the real AWS SDK implementation. */
    private String provider = "mock";
    private String region = "ap-south-1";
    private String profile = "";
    private String discoveryTagKey = "CloudOpsMonitored";
    private String discoveryTagValue = "true";
    private boolean discoveryTagFilterEnabled = true;
    private int apiTimeoutSeconds = 20;

    /**
     * Import the mock provider's instances at startup when the database has no resources yet,
     * so the dashboard is populated on first login. Ignored entirely when the provider is real —
     * a live AWS account is only ever read by an explicit, audited sync. Tests set this false
     * so they can assert on the first sync themselves.
     */
    private boolean seedMockResources = true;

    public boolean isAwsProvider() {
        return "aws".equalsIgnoreCase(provider);
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getDiscoveryTagKey() {
        return discoveryTagKey;
    }

    public void setDiscoveryTagKey(String discoveryTagKey) {
        this.discoveryTagKey = discoveryTagKey;
    }

    public String getDiscoveryTagValue() {
        return discoveryTagValue;
    }

    public void setDiscoveryTagValue(String discoveryTagValue) {
        this.discoveryTagValue = discoveryTagValue;
    }

    public boolean isDiscoveryTagFilterEnabled() {
        return discoveryTagFilterEnabled;
    }

    public void setDiscoveryTagFilterEnabled(boolean discoveryTagFilterEnabled) {
        this.discoveryTagFilterEnabled = discoveryTagFilterEnabled;
    }

    public int getApiTimeoutSeconds() {
        return apiTimeoutSeconds;
    }

    public void setApiTimeoutSeconds(int apiTimeoutSeconds) {
        this.apiTimeoutSeconds = apiTimeoutSeconds;
    }

    public boolean isSeedMockResources() {
        return seedMockResources;
    }

    public void setSeedMockResources(boolean seedMockResources) {
        this.seedMockResources = seedMockResources;
    }
}
