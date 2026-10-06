package com.cloudops.agent.entity;

/** Lifecycle state of a resource, mapped from the EC2 instance state name. */
public enum ResourceStatus {
    PENDING,
    RUNNING,
    STOPPING,
    STOPPED,
    SHUTTING_DOWN,
    TERMINATED,
    UNKNOWN;

    /** Maps an AWS instance state string ("running", "shutting-down", ...) to this enum. */
    public static ResourceStatus fromAws(String awsState) {
        if (awsState == null || awsState.isBlank()) {
            return UNKNOWN;
        }
        String normalized = awsState.trim().toUpperCase().replace('-', '_');
        for (ResourceStatus status : values()) {
            if (status.name().equals(normalized)) {
                return status;
            }
        }
        return UNKNOWN;
    }
}
