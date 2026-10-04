package com.ladygo.bookings.repository;

import java.util.List;
import java.util.UUID;
import com.ladygo.bookings.domain.RideOffer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideOfferRepository extends JpaRepository<RideOffer, UUID> {

    List<RideOffer> findByBookingId(UUID bookingId);

    boolean existsByBookingIdAndDriverId(UUID bookingId, UUID driverId);
}
