package com.cloudops.agent.exception;

/** 400 - the caller sent something invalid that bean validation could not catch. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
