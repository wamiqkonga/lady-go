package com.ladygo.rides.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.ladygo.rides.domain.RideFlag;
import com.ladygo.rides.domain.RideFlagType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideFlagRepository extends JpaRepository<RideFlag, UUID> {

    /** Weekly-per-rider cap check (docs/api/ride-lifecycle.md section 13b-1). */
    long countByCustomerIdAndTypeAndCreatedAtAfter(UUID customerId, RideFlagType type, Instant createdAtAfter);

    /** Per-rider/driver-pair cap check - tighter than the weekly cap above. */
    long countByCustomerIdAndDriverIdAndTypeAndCreatedAtAfter(
            UUID customerId, UUID driverId, RideFlagType type, Instant createdAtAfter);

    /** Undisputed cash-dispute flags block the auto-complete sweep (docs/api/ride-lifecycle.md section 8). */
    List<RideFlag> findByBookingIdAndType(UUID bookingId, RideFlagType type);
}
