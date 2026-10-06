package com.cloudops.agent.entity;

/**
 * Application roles. Spring Security sees these as authorities prefixed with
 * "ROLE_", e.g. ROLE_ADMIN, which is what @PreAuthorize("hasRole('ADMIN')") checks.
 *
 * VIEWER   - read-only dashboard access
 * OPERATOR - may register resources, trigger collection, acknowledge incidents,
 *            run AI analysis; may NOT approve a remediation
 * ADMIN    - everything, including approving and executing remediation actions
 */
public enum Role {
    VIEWER,
    OPERATOR,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
