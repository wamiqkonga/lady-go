package com.ladygo.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import com.ladygo.auth.domain.RefreshToken;
import com.ladygo.auth.repository.RefreshTokenRepository;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.common.exception.UnauthorizedException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserStatus;
import com.ladygo.users.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh token issue/rotate/revoke with reuse detection (docs/security/auth-design.md section 6).
 * Only the SHA-256 hash of a refresh token ever touches the database; the raw value is returned to the
 * caller exactly once, at issuance/rotation time.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final UserRepository users;
    private final ConfigurationService config;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, UserRepository users, ConfigurationService config) {
        this.repository = repository;
        this.users = users;
        this.config = config;
    }

    public record Rotated(UUID userId, String rawRefreshToken) {
    }

    @Transactional
    public String issue(UUID userId, String deviceId) {
        String raw = randomToken();
        RefreshToken entity = new RefreshToken();
        entity.setUserId(userId);
        entity.setDeviceId(deviceId);
        entity.setTokenHash(hash(raw));
        entity.setExpiresAt(Instant.now().plus(ttl()));
        repository.save(entity);
        return raw;
    }

    @Transactional
    public Rotated rotate(String rawRefreshToken) {
        RefreshToken existing = repository.findByTokenHash(hash(rawRefreshToken))
                .orElseThrow(UnauthorizedException::new);
        Instant now = Instant.now();

        if (existing.getRevokedAt() != null) {
            log.warn("operation=refresh_reuse_detected result=family_revoked userId={} deviceId={}",
                    existing.getUserId(), existing.getDeviceId());
            revokeFamily(existing.getUserId(), existing.getDeviceId(), now);
            throw new UnauthorizedException();
        }
        if (existing.getExpiresAt().isBefore(now)) {
            throw new UnauthorizedException();
        }

        User user = users.findById(existing.getUserId()).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            // Adversarial review finding (CRITICAL): a suspended/deleted user could keep refreshing
            // forever since status was never checked here. Kill the whole session, not just this call.
            revokeFamily(existing.getUserId(), existing.getDeviceId(), now);
            throw new ForbiddenException("This account is not active.");
        }

        String newRaw = randomToken();
        RefreshToken next = new RefreshToken();
        next.setUserId(existing.getUserId());
        next.setDeviceId(existing.getDeviceId());
        next.setTokenHash(hash(newRaw));
        next.setExpiresAt(now.plus(ttl()));
        repository.save(next);

        int rotated = repository.rotateIfActive(existing.getId(), next.getId(), now);
        if (rotated == 0) {
            // A concurrent call (another rotate, or a logout) already changed this token's state first.
            repository.delete(next);
            throw new UnauthorizedException();
        }
        return new Rotated(existing.getUserId(), newRaw);
    }

    @Transactional
    public void revoke(String rawRefreshToken) {
        repository.findByTokenHash(hash(rawRefreshToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(Instant.now());
                repository.save(token);
            }
        });
    }

    /**
     * Revokes every active refresh token for a user, regardless of device. Exposed for "sign out
     * everywhere" and as the hook point for automatic revoke-on-suspension (auth-design.md OQ-6) once
     * a user-suspension action exists (Phase 9) to call it.
     */
    @Transactional
    public void revokeAll(UUID userId) {
        repository.revokeAllByUserId(userId, Instant.now());
    }

    private void revokeFamily(UUID userId, String deviceId, Instant now) {
        if (deviceId != null) {
            repository.revokeAllByUserIdAndDeviceId(userId, deviceId, now);
        } else {
            repository.revokeAllByUserId(userId, now);
        }
    }

    private Duration ttl() {
        return config.getDays("refresh.token.ttl.days", Duration.ofDays(30));
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
