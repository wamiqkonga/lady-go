package com.ladygo.bookings.repository;

import java.util.List;
import java.util.UUID;
import com.ladygo.bookings.domain.BookingEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingEventRepository extends JpaRepository<BookingEvent, UUID> {

    List<BookingEvent> findByBookingIdOrderByCreatedAtAsc(UUID bookingId);

    /** True when the booking reached {@code toStatus} with a reason starting with {@code reasonPrefix}
     *  (used to tell an early-ended ride from a normal completion without changing the state machine). */
    boolean existsByBookingIdAndToStatusAndReasonStartingWith(
            UUID bookingId, com.ladygo.bookings.domain.BookingStatus toStatus, String reasonPrefix);
}
