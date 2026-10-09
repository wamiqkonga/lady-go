package com.ladygo.rides.service;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.bookings.service.BookingStatusChangedEvent;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.domain.RideStatus;
import com.ladygo.rides.repository.RideRepository;
import java.time.Instant;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps {@code rides.status}/{@code driver_id} in sync with every {@code BookingStatusChangedEvent}
 * that touches a ride's lifecycle - formerly {@code RideBootstrapListener}, which only handled
 * creation on {@code DRIVER_ACCEPTED} and explicitly left every other transition untouched ("this
 * listener never changes a ride's status" - its own prior javadoc). That gap is exactly what let a
 * cancelled booking's {@code rides.status} row sit at {@code ASSIGNED} forever (security review
 * finding against {@code TripShareService}, now fixed there by reading {@code Booking.status}
 * instead - but the underlying {@code Ride.status} column was still lying to any OTHER reader, so this
 * closes the root cause, not just that one symptom).
 *
 * <p>{@code rides.booking_id} is unique (V7) - at most one ride row ever exists per booking, so
 * "driver re-accepted after a bounce-back to SEARCHING_DRIVER with a different driver" (currently
 * unreachable - no caller transitions a booking from {@code DRIVER_ACCEPTED}/{@code DRIVER_ARRIVING}/
 * {@code DRIVER_ARRIVED} to {@code SEARCHING_DRIVER} today, though the state machine's {@code ALLOWED}
 * map permits it) must UPDATE the existing row's {@code driver_id} and revive its status back to
 * {@code ASSIGNED}, never create a second row. Handling this now, even though unreachable today,
 * rather than leaving a second drift trap for whenever that path is wired up.
 *
 * <p>Also found, same audit, same root cause shape (a one-time claim with no matching release once
 * the ride actually concludes): {@code DriverRepository.releaseFromOffer} (BUSY -&gt; ONLINE) was only
 * ever called from offer-reject/expire (before acceptance) - nothing called it after a ride actually
 * finished or got cancelled post-acceptance, so every driver got stuck at {@code BUSY} forever after
 * their first completed ride, with no self-service recovery
 * ({@code DriverAvailabilityService.setAvailability} explicitly refuses to change availability away
 * from {@code BUSY}/{@code ON_RIDE} directly). Released here too, at the same two points.
 */
@Component
public class RideStatusSyncListener {

    private final BookingRepository bookings;
    private final RideRepository rides;
    private final DriverRepository drivers;

    public RideStatusSyncListener(BookingRepository bookings, RideRepository rides, DriverRepository drivers) {
        this.bookings = bookings;
        this.rides = rides;
        this.drivers = drivers;
    }

    @EventListener
    @Transactional
    public void onBookingStatusChanged(BookingStatusChangedEvent event) {
        if (event.to() == BookingStatus.DRIVER_ACCEPTED) {
            onAccepted(event);
        } else if (event.to() == BookingStatus.CANCELLED || event.to() == BookingStatus.SEARCHING_DRIVER) {
            onAbandoned(event);
        } else if (event.to() == BookingStatus.RIDE_COMPLETED) {
            onRideCompleted(event);
        }
    }

    /** Creates the ride row on first acceptance, exactly as the old {@code RideBootstrapListener} did
     *  - or, if one already exists (a re-acceptance after a bounce-back), revives it for the new
     *  driver instead of trying to create a second row (which {@code uq_rides_booking} would reject). */
    private void onAccepted(BookingStatusChangedEvent event) {
        Booking booking = bookings.findById(event.bookingId()).orElseThrow();
        if (booking.getDriverId() == null) {
            return;
        }
        Ride ride = rides.findByBookingId(event.bookingId()).orElse(null);
        if (ride == null) {
            ride = new Ride();
            ride.setBookingId(booking.getId());
            ride.setCustomerId(booking.getCustomerId());
        } else if (ride.getStatus() == RideStatus.COMPLETED) {
            return; // never revive a genuinely finished ride - should be unreachable (booking.CANCELLED/
                     // COMPLETED have no path back to DRIVER_ACCEPTED), but a safe no-op if it ever happened.
        } else {
            // Reviving a previously-abandoned (CANCELLED) row for a re-acceptance - clear the earlier
            // abandonment's timestamp so this ride's new life starts clean, not carrying over a stale
            // completedAt from when it was last marked abandoned.
            ride.setCompletedAt(null);
            ride.setStartedAt(null);
        }
        ride.setDriverId(booking.getDriverId());
        ride.setStatus(RideStatus.ASSIGNED);
        rides.saveAndFlush(ride);
    }

    /** {@code CANCELLED} (every real cancel path today) and {@code SEARCHING_DRIVER} (currently
     *  unreachable post-acceptance, but allowed by the state machine) both mean "this particular
     *  driver assignment is over" - mark the ride row CANCELLED so it stops looking live to any
     *  reader, unless it's already COMPLETED (never un-finish a genuinely completed ride), and free
     *  the driver back to ONLINE regardless (they're no longer tied to this booking either way). */
    private void onAbandoned(BookingStatusChangedEvent event) {
        rides.findByBookingId(event.bookingId()).ifPresent(ride -> {
            if (ride.getStatus() == RideStatus.COMPLETED) {
                return;
            }
            ride.setStatus(RideStatus.CANCELLED);
            if (ride.getCompletedAt() == null) {
                ride.setCompletedAt(Instant.now());
            }
            rides.save(ride);
            drivers.releaseFromOffer(ride.getDriverId()); // conditional (WHERE availabilityStatus = BUSY) - a safe no-op if the driver wasn't BUSY.
        });
    }

    /** The ride itself just ended (normal completion or either early-end variant) -
     *  {@code completeRideInternal} already set {@code ride.status = COMPLETED}; this is purely the
     *  driver-release half of that same moment. */
    private void onRideCompleted(BookingStatusChangedEvent event) {
        rides.findByBookingId(event.bookingId())
                .ifPresent(ride -> drivers.releaseFromOffer(ride.getDriverId()));
    }
}
