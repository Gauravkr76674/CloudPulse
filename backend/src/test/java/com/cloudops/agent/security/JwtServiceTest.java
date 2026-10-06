package com.cloudops.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import com.cloudops.agent.config.SecurityProperties;

import io.jsonwebtoken.JwtException;

/**
 * The token is the only thing standing between the internet and the remediation endpoints,
 * so the tests here are mostly about rejection: a tampered signature, a foreign secret, an
 * expired token, a token minted by a different issuer. Each of those must fail closed.
 */
class JwtServiceTest {

    private static final String SECRET = "a-test-signing-secret-that-is-long-enough-32";
    private static final String OTHER_SECRET = "a-completely-different-secret-also-32-bytes";

    private UserDetails admin;
    private UserDetails viewer;
    private JwtService service;

    private SecurityProperties properties(String secret, int expirationMinutes, String issuer) {
        SecurityProperties properties = new SecurityProperties();
        properties.setJwtSecret(secret);
        properties.setJwtExpirationMinutes(expirationMinutes);
        properties.setIssuer(issuer);
        return properties;
    }

    @BeforeEach
    void setUp() {
        admin = User.withUsername("admin").password("ignored").authorities("ROLE_ADMIN").build();
        viewer = User.withUsername("viewer").password("ignored").authorities("ROLE_VIEWER").build();
        service = new JwtService(properties(SECRET, 30, "cloudops-ai-agent"));
    }

    // ------------------------------------------------------------------
    // the happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a token round-trips the username and the role without the ROLE_ prefix")
    void tokenCarriesUsernameAndRole() {
        String token = service.generateToken(admin);

        assertThat(token).isNotBlank();
        // three dot-separated segments: header, payload, signature
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(service.extractUsername(token)).isEqualTo("admin");
        assertThat(service.extractRole(token)).isEqualTo("ADMIN");
        assertThat(service.isTokenValid(token, admin)).isTrue();
    }

    @Test
    @DisplayName("the password never reaches the token")
    void tokenDoesNotLeakCredentials() {
        UserDetails user = User.withUsername("admin")
                .password("$2a$10$averyrealbcryptlookinghash")
                .authorities("ROLE_ADMIN")
                .build();

        assertThat(service.generateToken(user)).doesNotContain("averyrealbcryptlookinghash");
    }

    @Test
    @DisplayName("a user with no ROLE_ prefix or no authority at all still gets a role claim")
    void roleClaimHasASafeDefault() {
        UserDetails odd = User.withUsername("odd").password("x").authorities("OPERATOR").build();
        assertThat(service.extractRole(service.generateToken(odd))).isEqualTo("OPERATOR");

        UserDetails none = User.withUsername("none").password("x").authorities(new String[0]).build();
        // the least privileged role, never ADMIN
        assertThat(service.extractRole(service.generateToken(none))).isEqualTo("VIEWER");
    }

    // ------------------------------------------------------------------
    // failing closed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a token is only valid for the user it was issued to")
    void tokenIsBoundToItsSubject() {
        String adminToken = service.generateToken(admin);

        assertThat(service.isTokenValid(adminToken, viewer)).isFalse();
    }

    @Test
    @DisplayName("a tampered signature is rejected")
    void tamperedSignatureIsRejected() {
        String[] parts = service.generateToken(admin).split("\\.");
        char last = parts[2].charAt(parts[2].length() - 1);
        String forged = parts[0] + "." + parts[1] + "."
                + parts[2].substring(0, parts[2].length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThat(service.isTokenValid(forged, admin)).isFalse();
        assertThatThrownBy(() -> service.extractUsername(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a payload edited to escalate the role is rejected")
    void tamperedPayloadIsRejected() {
        String[] parts = service.generateToken(viewer).split("\\.");
        // re-encoding the payload with a different role invalidates the signature
        String escalated = parts[0] + "."
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        new String(java.util.Base64.getUrlDecoder().decode(parts[1]))
                                .replace("VIEWER", "ADMIN")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThat(service.isTokenValid(escalated, viewer)).isFalse();
        assertThatThrownBy(() -> service.extractRole(escalated)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token signed with another secret is rejected")
    void foreignSecretIsRejected() {
        JwtService attacker = new JwtService(properties(OTHER_SECRET, 30, "cloudops-ai-agent"));
        String foreignToken = attacker.generateToken(admin);

        assertThat(service.isTokenValid(foreignToken, admin)).isFalse();
        assertThatThrownBy(() -> service.extractUsername(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token from a different issuer is rejected even with the right secret")
    void foreignIssuerIsRejected() {
        JwtService otherApp = new JwtService(properties(SECRET, 30, "some-other-service"));
        String foreignToken = otherApp.generateToken(admin);

        assertThat(service.isTokenValid(foreignToken, admin)).isFalse();
        assertThatThrownBy(() -> service.extractUsername(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokenIsRejected() {
        // negative expiry puts the exp claim in the past
        JwtService shortLived = new JwtService(properties(SECRET, -1, "cloudops-ai-agent"));
        String stale = shortLived.generateToken(admin);

        assertThat(service.isTokenValid(stale, admin)).isFalse();
        assertThatThrownBy(() -> service.extractUsername(stale)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("garbage input returns false instead of blowing up the filter chain")
    void garbageIsNotValid() {
        for (String garbage : new String[] {"", "   ", "not-a-token", "a.b.c",
                "Bearer eyJhbGciOiJIUzI1NiJ9"}) {
            assertThat(service.isTokenValid(garbage, admin))
                    .as("token: '%s'", garbage)
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------
    // configuration guards
    // ------------------------------------------------------------------

    @Test
    @DisplayName("startup fails loudly when the JWT secret is missing or too weak")
    void weakSecretIsRefusedAtStartup() {
        assertThatThrownBy(() -> new JwtService(properties(null, 30, "cloudops-ai-agent")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");

        assertThatThrownBy(() -> new JwtService(properties("   ", 30, "cloudops-ai-agent")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");

        assertThatThrownBy(() -> new JwtService(properties("too-short", 30, "cloudops-ai-agent")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }
}
