package com.ladygo.drivers.service;

import java.time.Instant;
import java.util.Optional;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingEventRepository;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.config.ConfigurationService;
import com.ladygo.drivers.repository.RideEarningRepository;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.repository.RideRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes the one {@code ride_earnings} row for a completed ride (docs/api/driver-earnings-ratings.md
 * sections 4.2 and 5). Called from {@code RideStatusSyncListener} inside the ride-completion
 * transaction. Idempotent: the insert is guarded by the unique ride id, so a replayed completion event
 * is a harmless no-op. Logs ids and amounts only - never rider data.
 */
@Service
public class EarningsRecorder {

    static final String COMMISSION_BPS_KEY = "earnings.commission.bps";
    /** Prefix of the booking_events reason written by RideLifecycleService for either early-end variant. */
    static final String EARLY_END_REASON_PREFIX = "ride_ended_early";

    private static final Logger log = LoggerFactory.getLogger(EarningsRecorder.class);

    private final BookingRepository bookings;
    private final BookingEventRepository bookingEvents;
    private final RideRepository rides;
    private final RideEarningRepository earnings;
    private final ConfigurationService config;

    public EarningsRecorder(BookingRepository bookings, BookingEventRepository bookingEvents, RideRepository rides,
                            RideEarningRepository earnings, ConfigurationService config) {
        this.bookings = bookings;
        this.bookingEvents = bookingEvents;
        this.rides = rides;
        this.earnings = earnings;
        this.config = config;
    }

    /** @return true if a new row was written, false if skipped or already present. */
    public boolean recordForCompletedBooking(UUID bookingId) {
        Booking booking = bookings.findById(bookingId).orElse(null);
        Ride ride = rides.findByBookingId(bookingId).orElse(null);
        if (booking == null || ride == null || booking.getFinalFare() == null) {
            return false; // no confirmed final fare, so nothing was earned
        }
        // v1 (approved decision Q3): early ends and cancellations earn 0, so they get no earnings row.
        if (bookingEvents.existsByBookingIdAndToStatusAndReasonStartingWith(
                bookingId, BookingStatus.RIDE_COMPLETED, EARLY_END_REASON_PREFIX)) {
            return false;
        }
        long gross = booking.getFinalFare();
        Optional<Integer> configured = config.findInt(COMMISSION_BPS_KEY);
        Integer bps = configured.filter(v -> v >= 0 && v <= CommissionCalculator.MAX_BPS).orElse(null);
        if (configured.isPresent() && bps == null) {
            log.error("operation=earnings_commission_config result=invalid_ignored key={}", COMMISSION_BPS_KEY);
        }
        long commission = CommissionCalculator.commission(gross, bps);
        int inserted = earnings.insertIfAbsent(
                ride.getId(), ride.getDriverId(), gross, bps == null ? 0 : bps, commission, gross - commission,
                booking.getCurrency(), booking.getPaymentMethod().name(), Instant.now());
        log.info("operation=earnings_recorded rideId={} result={}", ride.getId(), inserted == 1 ? "inserted" : "duplicate");
        return inserted == 1;
    }
}
