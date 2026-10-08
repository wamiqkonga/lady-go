package com.ladygo.bookings.service;

import static com.ladygo.bookings.domain.BookingStatus.CANCELLED;
import static com.ladygo.bookings.domain.BookingStatus.COMPLETED;
import static com.ladygo.bookings.domain.BookingStatus.DRIVER_ACCEPTED;
import static com.ladygo.bookings.domain.BookingStatus.DRIVER_ARRIVED;
import static com.ladygo.bookings.domain.BookingStatus.DRIVER_ARRIVING;
import static com.ladygo.bookings.domain.BookingStatus.DRIVER_ASSIGNED;
import static com.ladygo.bookings.domain.BookingStatus.PAYMENT_PENDING;
import static com.ladygo.bookings.domain.BookingStatus.REQUESTED;
import static com.ladygo.bookings.domain.BookingStatus.RIDE_COMPLETED;
import static com.ladygo.bookings.domain.BookingStatus.RIDE_STARTED;
import static com.ladygo.bookings.domain.BookingStatus.SEARCHING_DRIVER;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingEvent;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingEventRepository;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.common.domain.ActorType;
import com.ladygo.common.exception.BookingStateException;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.common.exception.ResourceNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place {@code bookings.status} is ever changed (docs/api/booking.md section 4;
 * docs/architecture/backend-architecture.md section 4). The transition table matches
 * docs/product/user-flows.md section 1 exactly.
 */
@Service
public class BookingStateMachine {

    private static final Map<BookingStatus, Set<BookingStatus>> ALLOWED = Map.ofEntries(
            Map.entry(REQUESTED, Set.of(SEARCHING_DRIVER, CANCELLED)),
            Map.entry(SEARCHING_DRIVER, Set.of(DRIVER_ASSIGNED, CANCELLED)),
            Map.entry(DRIVER_ASSIGNED, Set.of(DRIVER_ACCEPTED, SEARCHING_DRIVER, CANCELLED)),
            Map.entry(DRIVER_ACCEPTED, Set.of(DRIVER_ARRIVING, SEARCHING_DRIVER, CANCELLED)),
            Map.entry(DRIVER_ARRIVING, Set.of(DRIVER_ARRIVED, SEARCHING_DRIVER, CANCELLED)),
            Map.entry(DRIVER_ARRIVED, Set.of(RIDE_STARTED, SEARCHING_DRIVER, CANCELLED)),
            Map.entry(RIDE_STARTED, Set.of(RIDE_COMPLETED)), // no CANCELLED - support/safety path, Phase 10
            Map.entry(RIDE_COMPLETED, Set.of(PAYMENT_PENDING)),
            Map.entry(PAYMENT_PENDING, Set.of(COMPLETED)),
            Map.entry(COMPLETED, Set.<BookingStatus>of()),
            Map.entry(CANCELLED, Set.<BookingStatus>of()));

    private final BookingRepository bookings;
    private final BookingEventRepository events;
    private final ApplicationEventPublisher publisher;

    public BookingStateMachine(
            BookingRepository bookings, BookingEventRepository events, ApplicationEventPublisher publisher) {
        this.bookings = bookings;
        this.events = events;
        this.publisher = publisher;
    }

    @Transactional
    public Booking transition(UUID bookingId, BookingStatus to, ActorType actorType, UUID actorId, String reason) {
        return transition(bookingId, to, actorType, actorId, reason, null);
    }

    @Transactional
    public Booking transition(UUID bookingId, BookingStatus to, ActorType actorType, UUID actorId, String reason,
            Map<String, Object> metadata) {
        Booking booking = bookings.findById(bookingId).orElseThrow(ResourceNotFoundException::new);
        BookingStatus from = booking.getStatus();

        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new BookingStateException();
        }
        authorise(actorType, to);

        booking.setStatus(to);
        if (to == CANCELLED) {
            // ck_bookings_cancelled requires cancelled_by whenever status = CANCELLED - this is the
            // bookings row's own column, separate from (and in addition to) the booking_events record
            // of who/why below.
            booking.setCancelledBy(actorType);
            booking.setCancelReason(reason);
        }
        bookings.save(booking); // @Version on Booking: a concurrent transition fails with a clear conflict.

        BookingEvent event = new BookingEvent();
        event.setBookingId(bookingId);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setActorType(actorType);
        event.setActorId(actorId);
        event.setReason(reason);
        event.setMetadata(metadata);
        events.save(event);

        publisher.publishEvent(new BookingStatusChangedEvent(bookingId, from, to));
        return booking;
    }

    private static final Set<BookingStatus> CUSTOMER_ALLOWED_TARGETS = Set.of(
            CANCELLED,
            // docs/api/ride-lifecycle.md section 13b-4: the rider-confirm-arrival gate bypass.
            DRIVER_ARRIVED,
            // docs/api/ride-lifecycle.md section 13b-1/§4: a rider may end their own ride early.
            RIDE_COMPLETED);

    /**
     * CUSTOMER may only ever initiate a transition to CANCELLED, DRIVER_ARRIVED (the confirm-arrival
     * gate bypass), or RIDE_COMPLETED (ending their own ride early) - every other transition is driven
     * by SYSTEM (matching, Phase 6) or DRIVER/ADMIN. The {@code ALLOWED} map above already constrains
     * which {@code from} state makes each of these reachable (e.g. RIDE_COMPLETED only from
     * RIDE_STARTED), so this only widens the actor check, not the state machine itself.
     */
    private void authorise(ActorType actorType, BookingStatus to) {
        if (actorType == ActorType.CUSTOMER && !CUSTOMER_ALLOWED_TARGETS.contains(to)) {
            throw new ForbiddenException("Customers may only cancel a booking, confirm driver arrival, or end their own ride.");
        }
    }
}
