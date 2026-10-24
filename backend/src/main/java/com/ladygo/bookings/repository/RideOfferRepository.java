package com.ladygo.bookings.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.bookings.domain.OfferStatus;
import com.ladygo.bookings.domain.RideOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RideOfferRepository extends JpaRepository<RideOffer, UUID> {

    List<RideOffer> findByBookingId(UUID bookingId);

    boolean existsByBookingIdAndDriverId(UUID bookingId, UUID driverId);

    /** A booking is never offered to two drivers at once - at most one row, by design. */
    Optional<RideOffer> findByBookingIdAndStatus(UUID bookingId, OfferStatus status);

    /** The one offer currently sitting with this driver, if any - used by accept/reject. */
    Optional<RideOffer> findByDriverIdAndStatus(UUID driverId, OfferStatus status);

    /** Swept periodically - still OFFERED but past its response window. */
    List<RideOffer> findByStatusAndExpiresAtBefore(OfferStatus status, Instant cutoff);

    /** Atomic claim on the EXPIRE side, same shape as {@code DriverRepository.claimForOffer} - 0 rows
     *  affected means a driver's {@code accept()} already won this exact offer between the sweep's
     *  SELECT and this UPDATE (adversarial review finding, HIGH: without this guard, the sweep would
     *  unconditionally overwrite an already-ACCEPTED offer back to EXPIRED and spuriously release the
     *  now-committed driver back to ONLINE, making them claimable by an unrelated booking's search
     *  while already en route). The caller must skip releasing the driver/advancing the booking when
     *  this returns 0. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE RideOffer o SET o.status = com.ladygo.bookings.domain.OfferStatus.EXPIRED, "
            + "o.respondedAt = :respondedAt WHERE o.id = :id AND o.status = com.ladygo.bookings.domain.OfferStatus.OFFERED")
    int expireIfStillOffered(@Param("id") UUID id, @Param("respondedAt") Instant respondedAt);
}
