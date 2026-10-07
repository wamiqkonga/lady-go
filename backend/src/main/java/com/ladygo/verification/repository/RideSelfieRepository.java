package com.ladygo.verification.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.verification.domain.RideSelfie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RideSelfieRepository extends JpaRepository<RideSelfie, UUID> {

    Optional<RideSelfie> findByBookingId(UUID bookingId);

    /** docs/api/driver-onboarding.md section 7 - no review step for a ride selfie, so the retention
     *  clock runs from captured_at instead of reviewed_at. */
    List<RideSelfie> findByCapturedAtBeforeAndContentDeletedAtIsNull(Instant cutoff);

    /**
     * Atomically claims a fresh, unused selfie for a booking - the {@code booking_id IS NULL} guard
     * plus {@code uq_ride_selfies_booking} is what makes "exactly one booking per selfie" race-safe,
     * same pattern as the booking idempotency key (docs/api/driver-onboarding.md section 4). 0 rows
     * affected means the selfie doesn't exist, isn't this customer's, is already used, or is stale.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RideSelfie r SET r.bookingId = :bookingId "
            + "WHERE r.id = :selfieId AND r.customerId = :customerId AND r.bookingId IS NULL "
            + "AND r.capturedAt >= :notBefore")
    int claimForBooking(@Param("selfieId") UUID selfieId, @Param("customerId") UUID customerId,
            @Param("bookingId") UUID bookingId, @Param("notBefore") Instant notBefore);
}
