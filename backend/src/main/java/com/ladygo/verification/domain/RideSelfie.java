package com.ladygo.verification.domain;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A rider's live selfie, captured fresh per booking - not a one-time, admin-reviewed artifact
 * (docs/api/driver-onboarding.md section 4). {@code bookingId} starts null (just captured) and is set
 * atomically when a booking claims it; {@code uq_ride_selfies_booking} is what makes "exactly one
 * booking per selfie" race-safe.
 */
@Entity
@Table(name = "ride_selfies")
@Getter
@Setter
public class RideSelfie extends BaseEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "selfie_storage_key", length = 500, nullable = false)
    private String selfieStorageKey;

    @CreationTimestamp
    @Column(name = "captured_at", nullable = false, updatable = false)
    private Instant capturedAt;

    @Column(name = "content_deleted_at")
    private Instant contentDeletedAt;
}
