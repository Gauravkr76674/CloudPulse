package com.cloudops.agent.exception;

/** 404 - the requested entity does not exist. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException of(String entity, Object id) {
        return new NotFoundException(entity + " not found: " + id);
    }
}
