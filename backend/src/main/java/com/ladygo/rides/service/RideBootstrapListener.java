package com.ladygo.rides.service;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.bookings.service.BookingStatusChangedEvent;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.repository.RideRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the {@code rides} row the moment a driver accepts (status {@code ASSIGNED}) - purely so
 * {@code location_history} (foreign-keyed to {@code rides}) has somewhere to attach samples during the
 * ride, for Phase 6's location tracking. Ride start/complete/PIN lifecycle (the {@code STARTED} and
 * {@code COMPLETED} transitions) stay Phase 7's job - this listener never changes a ride's status.
 */
@Component
public class RideBootstrapListener {

    private final BookingRepository bookings;
    private final RideRepository rides;

    public RideBootstrapListener(BookingRepository bookings, RideRepository rides) {
        this.bookings = bookings;
        this.rides = rides;
    }

    @EventListener
    @Transactional
    public void onBookingStatusChanged(BookingStatusChangedEvent event) {
        if (event.to() != BookingStatus.DRIVER_ACCEPTED) {
            return;
        }
        if (rides.findByBookingId(event.bookingId()).isPresent()) {
            return; // already bootstrapped (e.g. a re-delivered event) - never create a second row.
        }
        Booking booking = bookings.findById(event.bookingId()).orElseThrow();
        if (booking.getDriverId() == null) {
            return;
        }
        Ride ride = new Ride();
        ride.setBookingId(booking.getId());
        ride.setDriverId(booking.getDriverId());
        ride.setCustomerId(booking.getCustomerId());
        rides.saveAndFlush(ride);
    }
}
