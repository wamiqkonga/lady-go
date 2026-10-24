package com.ladygo.rides.repository;

import java.util.Optional;
import java.util.UUID;
import com.ladygo.rides.domain.Ride;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRepository extends JpaRepository<Ride, UUID> {

    Optional<Ride> findByBookingId(UUID bookingId);
}
