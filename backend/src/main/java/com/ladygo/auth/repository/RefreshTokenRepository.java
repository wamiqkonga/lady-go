package com.ladygo.auth.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);

    /** Reuse detection (docs/security/auth-design.md section 6): revoke the whole device family. */
    // clearAutomatically: a bulk update bypasses the persistence context, so without this, a
    // RefreshToken already loaded earlier in the same transaction would keep stale in-memory field
    // values (classic Hibernate bulk-update-vs-session-cache pitfall) even though the DB row changed.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now WHERE r.userId = :userId AND r.deviceId = :deviceId "
            + "AND r.revokedAt IS NULL")
    int revokeAllByUserIdAndDeviceId(
            @Param("userId") UUID userId, @Param("deviceId") String deviceId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now WHERE r.userId = :userId AND r.revokedAt IS NULL")
    int revokeAllByUserId(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Atomic rotation: only succeeds if the token was still live when this runs. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now, r.replacedBy = :replacedBy WHERE r.id = :id "
            + "AND r.revokedAt IS NULL")
    int rotateIfActive(
            @Param("id") UUID id, @Param("replacedBy") UUID replacedBy, @Param("now") Instant now);
}
