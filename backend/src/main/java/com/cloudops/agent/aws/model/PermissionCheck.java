package com.cloudops.agent.aws.model;

/**
 * Result of an IAM permission pre-flight check.
 *
 * {@code evaluated == false} means the check itself could not run (for example the
 * caller is an assumed role that IAM cannot simulate). In that case the action is
 * allowed to proceed — AWS remains the real enforcement point — but the note is
 * recorded on the action so the operator can see the check was inconclusive.
 */
public record PermissionCheck(
        boolean allowed,
        boolean evaluated,
        String decision,
        String message) {

    public static PermissionCheck allowed(String action) {
        return new PermissionCheck(true, true, "allowed", "IAM allows " + action);
    }

    public static PermissionCheck denied(String action, String decision) {
        return new PermissionCheck(false, true, decision,
                "IAM would deny " + action + " (decision: " + decision + ")");
    }

    public static PermissionCheck notEvaluated(String message) {
        return new PermissionCheck(true, false, "notEvaluated", message);
    }
}
