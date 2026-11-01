package com.ladygo.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import com.ladygo.users.domain.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Issues and validates HS256 JWTs. The signing key comes only from the environment
 * (docs/security/auth-design.md section 10) and must be at least 256 bits - checked eagerly here so a
 * too-short or missing key fails application startup, not the first login attempt.
 */
@Component
public class JwtService {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_SCOPE = "scope";
    private static final String SCOPE_ENROLMENT = "ENROLMENT";
    private static final String CLAIM_PWD_OK = "pwdOk";
    private static final String CLAIM_MFA_OK = "mfaOk";
    private static final String CLAIM_STEP_UP_AT = "stepUpAt";

    private final javax.crypto.SecretKey key;

    public JwtService(@Value("${ladygo.jwt.secret}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "ladygo.jwt.secret (LADYGO_JWT_SECRET) must be at least 32 bytes (256 bits).");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    public String issueAccessToken(UUID userId, UserRole role, Duration ttl) {
        return issueAccessToken(userId, role, ttl, null);
    }

    /** {@code stepUpAt} (nullable) records when this session last re-proved a TOTP code. It is signed into the
     *  token, so it cannot be forged, and it lives only as long as the token - a refresh issues a token
     *  without it, which is deliberate: a step-up is never silently carried to a new token. */
    public String issueAccessToken(UUID userId, UserRole role, Duration ttl, Instant stepUpAt) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_ROLE, role.name());
        if (stepUpAt != null) {
            builder.claim(CLAIM_STEP_UP_AT, stepUpAt.getEpochSecond());
        }
        return builder
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public String issueEnrolmentToken(UUID userId, boolean passwordChanged, boolean mfaEnrolled, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_SCOPE, SCOPE_ENROLMENT)
                .claim(CLAIM_PWD_OK, passwordChanged)
                .claim(CLAIM_MFA_OK, mfaEnrolled)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** Returns empty if the token is missing, malformed, expired or has an invalid signature. */
    public java.util.Optional<AuthPrincipal> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            UUID userId = UUID.fromString(claims.getSubject());
            if (SCOPE_ENROLMENT.equals(claims.get(CLAIM_SCOPE, String.class))) {
                boolean pwdOk = Boolean.TRUE.equals(claims.get(CLAIM_PWD_OK, Boolean.class));
                boolean mfaOk = Boolean.TRUE.equals(claims.get(CLAIM_MFA_OK, Boolean.class));
                return java.util.Optional.of(AuthPrincipal.enrolment(userId, pwdOk, mfaOk));
            }
            UserRole role = UserRole.valueOf(claims.get(CLAIM_ROLE, String.class));
            Long stepUpEpoch = claims.get(CLAIM_STEP_UP_AT, Long.class);
            Instant stepUpAt = stepUpEpoch == null ? null : Instant.ofEpochSecond(stepUpEpoch);
            return java.util.Optional.of(AuthPrincipal.authenticated(userId, role, stepUpAt));
        } catch (JwtException | IllegalArgumentException ex) {
            return java.util.Optional.empty();
        }
    }

    /** Used only to schedule a WebSocket session's forced close at token expiry
     *  (docs/architecture/realtime-architecture.md section 4) - every other authorisation decision
     *  goes through {@link #parse}. Returns empty for the same invalid-token cases as {@link #parse}. */
    public java.util.Optional<Instant> expiryOf(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            Date expiration = claims.getExpiration();
            return expiration == null ? java.util.Optional.empty() : java.util.Optional.of(expiration.toInstant());
        } catch (JwtException | IllegalArgumentException ex) {
            return java.util.Optional.empty();
        }
    }
}
