package com.ladygo.bookings.repository;

import java.util.List;
import java.util.UUID;
import com.ladygo.bookings.domain.BookingEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingEventRepository extends JpaRepository<BookingEvent, UUID> {

    List<BookingEvent> findByBookingIdOrderByCreatedAtAsc(UUID bookingId);
}
