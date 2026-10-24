package com.ladygo.rides.repository;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import com.ladygo.rides.domain.Ride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RideRepository extends JpaRepository<Ride, UUID> {

    Optional<Ride> findByBookingId(UUID bookingId);

    /** Serialises concurrent rating submissions for one ride, so the once-per-rater check cannot race. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Ride r where r.id = :id")
    Optional<Ride> lockById(@Param("id") UUID id);
}
