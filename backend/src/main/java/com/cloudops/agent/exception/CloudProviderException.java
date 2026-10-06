package com.cloudops.agent.exception;

/** 502 - the cloud provider (AWS SDK) call failed. */
public class CloudProviderException extends RuntimeException {

    public CloudProviderException(String message) {
        super(message);
    }

    public CloudProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
