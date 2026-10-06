package com.cloudops.agent.dto;

/** POST /api/auth/login response. The token goes into the Authorization: Bearer header. */
public record LoginResponse(
        String token,
        String tokenType,
        long expiresInSeconds,
        UserDto user) {

    public static LoginResponse of(String token, long expiresInSeconds, UserDto user) {
        return new LoginResponse(token, "Bearer", expiresInSeconds, user);
    }
}
