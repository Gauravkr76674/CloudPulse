package com.cloudops.agent.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.cloudops.agent.entity.Role;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Static access to "who is calling right now", used by the audit trail and by
 * services that stamp a username onto a record (approvals, resolutions, actions).
 *
 * Background jobs (metric polling, auto-analysis) run without an authenticated user;
 * in that case {@link #username()} returns "system".
 */
public final class CurrentUser {

    public static final String SYSTEM = "system";

    private CurrentUser() {
    }

    /** The authenticated username, or "system" for scheduled/background work. */
    public static String username() {
        return authentication()
                .map(Authentication::getName)
                .filter(name -> !"anonymousUser".equals(name))
                .orElse(SYSTEM);
    }

    /** The caller's role, empty for background work. */
    public static Optional<Role> role() {
        return authentication()
                .map(auth -> auth.getAuthorities().stream()
                        .map(a -> a.getAuthority())
                        .filter(a -> a.startsWith("ROLE_"))
                        .map(a -> a.substring(5))
                        .findFirst()
                        .orElse(null))
                .flatMap(name -> {
                    if (name == null) {
                        return Optional.empty();
                    }
                    try {
                        return Optional.of(Role.valueOf(name));
                    } catch (IllegalArgumentException ex) {
                        return Optional.empty();
                    }
                });
    }

    public static boolean isAdmin() {
        return role().filter(r -> r == Role.ADMIN).isPresent();
    }

    /** Client IP for the audit log, honouring X-Forwarded-For when behind a proxy. */
    public static String ipAddress() {
        return currentRequest()
                .map(request -> {
                    String forwarded = request.getHeader("X-Forwarded-For");
                    if (forwarded != null && !forwarded.isBlank()) {
                        return forwarded.split(",")[0].trim();
                    }
                    return request.getRemoteAddr();
                })
                .orElse(null);
    }

    private static Optional<Authentication> authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated()
                ? Optional.empty()
                : Optional.of(authentication);
    }

    private static Optional<HttpServletRequest> currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return Optional.of(attributes.getRequest());
        }
        return Optional.empty();
    }
}
