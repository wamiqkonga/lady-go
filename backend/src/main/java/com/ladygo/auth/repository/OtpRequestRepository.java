package com.ladygo.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.auth.domain.OtpRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OtpRequestRepository extends JpaRepository<OtpRequest, UUID> {

    Optional<OtpRequest> findFirstByPhoneAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(String phone, OtpPurpose purpose);

    long countByPhoneAndCreatedAtAfter(String phone, Instant since);

    // clearAutomatically: these are bulk updates that bypass the persistence context: without this,
    // an OtpRequest already loaded earlier in the same transaction would keep stale in-memory field
    // values (classic Hibernate bulk-update-vs-session-cache pitfall) even though the DB row changed.

    /** Invalidates any earlier unconsumed code for this phone+purpose (approved change, see auth-design.md OQ-2). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE otp_requests SET consumed_at = :now WHERE phone = :phone AND purpose = :purpose "
            + "AND consumed_at IS NULL", nativeQuery = true)
    int invalidateUnconsumed(@Param("phone") String phone, @Param("purpose") String purpose, @Param("now") Instant now);

    /**
     * Single atomic conditional update: counts as the attempt and enforces expiry/attempt-cap/consumed-state
     * in the same statement, so two concurrent verify calls can't both succeed past the cap (auth-design.md
     * section 4.2). Returns the number of rows affected (0 or 1).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE otp_requests SET attempts = attempts + 1 WHERE id = :id AND consumed_at IS NULL "
            + "AND expires_at > :now AND attempts < :maxAttempts", nativeQuery = true)
    int incrementAttemptIfEligible(
            @Param("id") UUID id, @Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

    /** Atomically consumes the row; 0 rows affected means another concurrent call already consumed it. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE otp_requests SET consumed_at = :now WHERE id = :id AND consumed_at IS NULL",
            nativeQuery = true)
    int consumeIfUnconsumed(@Param("id") UUID id, @Param("now") Instant now);
}
