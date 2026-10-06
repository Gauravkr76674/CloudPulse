package com.cloudops.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds <code>cloudops.remediation.*</code> - the safety envelope around every AWS mutation. */
@ConfigurationProperties(prefix = "cloudops.remediation")
public class RemediationProperties {

    /** Global kill switch. When false, approvals are recorded but AWS is never called. */
    private boolean executionEnabled = true;
    /** Mutating actions always need an ADMIN approval; keep true outside of tests. */
    private boolean requireApproval = true;
    /** When true every execution is forced into dry-run mode. */
    private boolean forceDryRun = false;
    private int maxActionsPerResourcePerHour = 3;
    private boolean iamPreflightEnabled = true;

    public boolean isExecutionEnabled() {
        return executionEnabled;
    }

    public void setExecutionEnabled(boolean executionEnabled) {
        this.executionEnabled = executionEnabled;
    }

    public boolean isRequireApproval() {
        return requireApproval;
    }

    public void setRequireApproval(boolean requireApproval) {
        this.requireApproval = requireApproval;
    }

    public boolean isForceDryRun() {
        return forceDryRun;
    }

    public void setForceDryRun(boolean forceDryRun) {
        this.forceDryRun = forceDryRun;
    }

    public int getMaxActionsPerResourcePerHour() {
        return maxActionsPerResourcePerHour;
    }

    public void setMaxActionsPerResourcePerHour(int maxActionsPerResourcePerHour) {
        this.maxActionsPerResourcePerHour = maxActionsPerResourcePerHour;
    }

    public boolean isIamPreflightEnabled() {
        return iamPreflightEnabled;
    }

    public void setIamPreflightEnabled(boolean iamPreflightEnabled) {
        this.iamPreflightEnabled = iamPreflightEnabled;
    }
}
