package com.ladygo.bookings.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Page<Booking> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    List<Booking> findByStatus(BookingStatus status);

    boolean existsByDriverIdAndStatusIn(UUID driverId, Collection<BookingStatus> statuses);
}
