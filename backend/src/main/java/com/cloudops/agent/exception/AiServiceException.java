package com.cloudops.agent.exception;

/** 502 - the AI provider failed, timed out, or returned something unparseable. */
public class AiServiceException extends RuntimeException {

    public AiServiceException(String message) {
        super(message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
