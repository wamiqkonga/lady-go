package com.ladygo.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.auth.domain.AdminPasswordReset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AdminPasswordResetRepository extends JpaRepository<AdminPasswordReset, UUID> {

    Optional<AdminPasswordReset> findByTokenHash(String tokenHash);

    /** Marks every still-open link of a user as used, so only the newest link (or none, after a reset) works. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AdminPasswordReset r SET r.usedAt = :now WHERE r.userId = :userId AND r.usedAt IS NULL")
    int invalidateOpenForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Atomically consumes a link; 0 means it was already used or has expired (two racing resets cannot both win). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AdminPasswordReset r SET r.usedAt = :now WHERE r.id = :id AND r.usedAt IS NULL AND r.expiresAt > :now")
    int consumeIfActive(@Param("id") UUID id, @Param("now") Instant now);
}
