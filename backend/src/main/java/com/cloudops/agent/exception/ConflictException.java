package com.cloudops.agent.exception;

/** 409 - e.g. registering a resource id that already exists. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
