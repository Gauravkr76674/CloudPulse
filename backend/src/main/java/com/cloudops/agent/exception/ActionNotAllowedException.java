package com.cloudops.agent.exception;

/**
 * 422 - a remediation guardrail refused the request (unsafe action, wrong resource
 * type, rate limit, kill switch, missing IAM permission).
 */
public class ActionNotAllowedException extends RuntimeException {

    public ActionNotAllowedException(String message) {
        super(message);
    }
}
