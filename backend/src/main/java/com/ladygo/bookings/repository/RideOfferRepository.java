package com.ladygo.bookings.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.bookings.domain.OfferStatus;
import com.ladygo.bookings.domain.RideOffer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideOfferRepository extends JpaRepository<RideOffer, UUID> {

    List<RideOffer> findByBookingId(UUID bookingId);

    boolean existsByBookingIdAndDriverId(UUID bookingId, UUID driverId);

    /** A booking is never offered to two drivers at once - at most one row, by design. */
    Optional<RideOffer> findByBookingIdAndStatus(UUID bookingId, OfferStatus status);

    /** The one offer currently sitting with this driver, if any - used by accept/reject. */
    Optional<RideOffer> findByDriverIdAndStatus(UUID driverId, OfferStatus status);

    /** Swept periodically - still OFFERED but past its response window. */
    List<RideOffer> findByStatusAndExpiresAtBefore(OfferStatus status, Instant cutoff);
}
