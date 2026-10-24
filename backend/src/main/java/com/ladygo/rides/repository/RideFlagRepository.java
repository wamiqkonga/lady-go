package com.ladygo.rides.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.ladygo.rides.domain.RideFlag;
import com.ladygo.rides.domain.RideFlagType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RideFlagRepository extends JpaRepository<RideFlag, UUID>, JpaSpecificationExecutor<RideFlag> {

    /** Weekly-per-rider cap check (docs/api/ride-lifecycle.md section 13b-1). */
    long countByCustomerIdAndTypeAndCreatedAtAfter(UUID customerId, RideFlagType type, Instant createdAtAfter);

    /** Per-rider/driver-pair cap check - tighter than the weekly cap above. */
    long countByCustomerIdAndDriverIdAndTypeAndCreatedAtAfter(
            UUID customerId, UUID driverId, RideFlagType type, Instant createdAtAfter);

    /** True when an unreviewed flag of this type already exists for the pair since the given time. */
    boolean existsByCustomerIdAndDriverIdAndTypeAndAdminReviewedAtIsNullAndCreatedAtAfter(
            UUID customerId, UUID driverId, RideFlagType type, Instant createdAtAfter);

    /** Undisputed cash-dispute flags block the auto-complete sweep (docs/api/ride-lifecycle.md section 8). */
    List<RideFlag> findByBookingIdAndType(UUID bookingId, RideFlagType type);

    /** Admin ride-detail view (docs/product/admin-web-design.md section 4). */
    List<RideFlag> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    /** Admin rides-list "open flags" column - counts only, no need to load full rows. */
    long countByBookingIdAndAdminReviewedAtIsNull(UUID bookingId);
}
