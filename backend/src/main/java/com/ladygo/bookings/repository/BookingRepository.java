package com.ladygo.bookings.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Page<Booking> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    List<Booking> findByStatus(BookingStatus status);

    boolean existsByDriverIdAndStatusIn(UUID driverId, Collection<BookingStatus> statuses);

    /** At most one row, guaranteed by {@code uq_bookings_one_active_per_driver}. */
    Optional<Booking> findByDriverIdAndStatusIn(UUID driverId, Collection<BookingStatus> statuses);

    /** docs/api/booking.md section 2 (B3): idempotent booking creation lookup. */
    Optional<Booking> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    /** Atomic claim on the dispute side, same shape as {@code RideOfferRepository.expireIfStillOffered}
     *  - security review finding: a plain read-then-write in {@code RideLifecycleService.disputeCash}
     *  had no guard against a repeat/concurrent call, so two overlapping disputes on the same booking
     *  could each insert their own {@link com.ladygo.rides.domain.RideFlag}, duplicating the admin
     *  queue entry. 0 rows affected means this booking was already disputed (by this call or a
     *  concurrent one) - the caller must treat that as "already disputed", not raise a second flag. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Booking b SET b.cashDisputedAt = :disputedAt WHERE b.id = :id AND b.cashDisputedAt IS NULL")
    int markCashDisputedIfNotAlready(@Param("id") UUID id, @Param("disputedAt") Instant disputedAt);
}
