package com.cloudops.agent.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import com.cloudops.agent.config.SecurityProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;

/**
 * Wraps jjwt 0.12.x to issue and validate HS256 tokens.
 * The secret is read from {@code cloudops.security.jwt-secret} which must be
 * sourced from the JWT_SECRET environment variable in every real deployment.
 */
@Service
public class JwtService {

    private static final String CLAIM_ROLE = "role";

    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final int COMPACT_SEGMENTS = 3;

    private final SecretKey signingKey;
    private final long expirationMillis;
    private final String issuer;

    public JwtService(SecurityProperties securityProperties) {
        String secret = securityProperties.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret is not configured. Set the JWT_SECRET environment variable.");
        }
        // HS256 requires >= 32 bytes; the application.yml default is a dev placeholder
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT secret is too short — must be at least 32 bytes for HS256.");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMillis = securityProperties.getJwtExpirationMinutes() * 60_000L;
        this.issuer = securityProperties.getIssuer();
    }

    /**
     * Generates a signed token carrying the user's username and role.
     * The role value is the enum name only (e.g. "ADMIN"), not the "ROLE_" prefixed
     * authority string; the filter re-adds the prefix on the way back in.
     */
    public String generateToken(UserDetails userDetails) {
        Instant now = Instant.now();
        // Extract the role name from the first granted authority, stripping "ROLE_"
        String role = userDetails.getAuthorities().stream()
                .findFirst()
                .map(a -> {
                    String authority = a.getAuthority();
                    return authority.startsWith("ROLE_") ? authority.substring(5) : authority;
                })
                .orElse("VIEWER");

        return Jwts.builder()
                .subject(userDetails.getUsername())
                .issuer(issuer)
                .claim(CLAIM_ROLE, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMillis)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Extracts the username claim from a token.
     *
     * @throws JwtException if the token is invalid, expired, or has the wrong issuer
     */
    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Extracts the role name (without "ROLE_" prefix) from the token.
     */
    public String extractRole(String token) {
        return parseClaims(token).get(CLAIM_ROLE, String.class);
    }

    /**
     * Returns true if the token is not expired and the subject matches the user.
     */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            Claims claims = parseClaims(token);
            return claims.getSubject().equals(userDetails.getUsername())
                    && claims.getExpiration().after(new Date());
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        requireCanonicalCompactForm(token);
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Requires the strict JOSE compact encoding: exactly three non-empty base64url segments,
     * unpadded and canonically encoded.
     *
     * <p>Why this is not redundant with the signature check. An HS256 signature is 32 bytes,
     * which is 43 base64url characters — 258 bits of alphabet for 256 bits of data, so the
     * final character carries four significant bits followed by two bits that must be zero.
     * Base64 decoders (both {@link Base64} and the one inside jjwt) silently discard those
     * two bits, so {@code ...A} and {@code ...B} decode to the identical 32 bytes and both
     * verify. The signature is not actually forged, but one signature ends up with several
     * valid spellings, and anything that treats the compact string as an identity — a
     * revocation list, an audit record, a rate-limit or cache key — can be walked straight
     * past by re-spelling a token. Rejecting the non-canonical spelling closes that off, and
     * costs one decode/encode round trip per request.
     *
     * @throws MalformedJwtException if the token is not in strict compact form
     */
    private void requireCanonicalCompactForm(String token) {
        if (token == null || token.isBlank()) {
            throw new MalformedJwtException("Token is empty");
        }
        String[] segments = token.split("\\.", -1);
        if (segments.length != COMPACT_SEGMENTS) {
            throw new MalformedJwtException(
                    "Token must have exactly " + COMPACT_SEGMENTS + " base64url segments");
        }
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                throw new MalformedJwtException("Token segment " + (i + 1) + " is empty");
            }
            byte[] decoded;
            try {
                decoded = URL_DECODER.decode(segment);
            } catch (IllegalArgumentException ex) {
                // The segment itself is never logged: it is part of a credential.
                throw new MalformedJwtException("Token segment " + (i + 1) + " is not base64url");
            }
            if (!URL_ENCODER.encodeToString(decoded).equals(segment)) {
                throw new MalformedJwtException(
                        "Token segment " + (i + 1) + " is not canonically base64url encoded");
            }
        }
    }
}
