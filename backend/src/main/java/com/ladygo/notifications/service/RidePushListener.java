package com.ladygo.notifications.service;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.bookings.service.BookingStatusChangedEvent;
import com.ladygo.common.domain.ActorType;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.notifications.domain.AppType;
import com.ladygo.notifications.push.PushMessage;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns ride transitions into pushes, after the transition has committed and off the request thread. The texts are
 * generic (they show on a locked screen); the data carries only the booking id, and the app fetches the rest.
 */
@Component
public class RidePushListener {

    private final BookingRepository bookings;
    private final CustomerRepository customers;
    private final DriverRepository drivers;
    private final PushNotificationService push;

    public RidePushListener(BookingRepository bookings, CustomerRepository customers, DriverRepository drivers,
            PushNotificationService push) {
        this.bookings = bookings;
        this.customers = customers;
        this.drivers = drivers;
        this.push = push;
    }

    /** Who gets which message for a transition. Pure, so the whole table is unit-tested. */
    public record Notice(AppType audience, String eventType, String title, String body) {
    }

    /** Plain comparisons, not a switch: a switch over an enum compiles to ordinal(), which the architecture test forbids. */
    public static Optional<Notice> noticeFor(BookingStatus to, ActorType cancelledBy, boolean hasDriver) {
        if (to == BookingStatus.DRIVER_ASSIGNED) {
            return Optional.of(new Notice(AppType.DRIVER, "RIDE_OFFER",
                    "New ride request", "A rider near you needs a ride. Open Lady Go to respond."));
        }
        if (to == BookingStatus.DRIVER_ACCEPTED) {
            return Optional.of(new Notice(AppType.CUSTOMER, "DRIVER_ACCEPTED",
                    "Your driver is on the way", "Open Lady Go to see where she is."));
        }
        if (to == BookingStatus.DRIVER_ARRIVED) {
            return Optional.of(new Notice(AppType.CUSTOMER, "DRIVER_ARRIVED",
                    "Your driver has arrived", "Open Lady Go for your ride PIN."));
        }
        if (to == BookingStatus.RIDE_COMPLETED) {
            return Optional.of(new Notice(AppType.CUSTOMER, "RATE_RIDE",
                    "How was your ride?", "Tell us about your trip in a few taps."));
        }
        if (to == BookingStatus.CANCELLED) {
            return cancelledNotice(cancelledBy, hasDriver);
        }
        return Optional.empty();
    }

    private static Optional<Notice> cancelledNotice(ActorType cancelledBy, boolean hasDriver) {
        if (cancelledBy == ActorType.DRIVER) {
            return Optional.of(new Notice(AppType.CUSTOMER, "RIDE_CANCELLED_BY_DRIVER",
                    "Your driver cancelled", "Open Lady Go to book another ride."));
        }
        if (cancelledBy == ActorType.CUSTOMER && hasDriver) {
            return Optional.of(new Notice(AppType.DRIVER, "RIDE_CANCELLED_BY_RIDER",
                    "The rider cancelled", "This ride was cancelled."));
        }
        if (cancelledBy == ActorType.SYSTEM) {
            return Optional.of(new Notice(AppType.CUSTOMER, "NO_DRIVER_FOUND",
                    "No driver available", "We could not find a driver this time. Please try again."));
        }
        return Optional.empty();
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingStatusChanged(BookingStatusChangedEvent event) {
        Booking booking = bookings.findById(event.bookingId()).orElse(null);
        if (booking == null) {
            return;
        }
        Optional<Notice> notice = noticeFor(event.to(), booking.getCancelledBy(), booking.getDriverId() != null);
        if (notice.isEmpty()) {
            return;
        }
        Notice n = notice.get();
        Optional<UUID> recipient = n.audience() == AppType.CUSTOMER
                ? customers.findById(booking.getCustomerId()).map(c -> c.getUserId())
                : Optional.ofNullable(booking.getDriverId()).flatMap(drivers::findById).map(d -> d.getUserId());
        recipient.ifPresent(userId -> push.notifyUser(userId, n.audience(),
                new PushMessage(n.eventType(), n.title(), n.body(), Map.of("bookingId", booking.getId().toString()))));
    }
}
