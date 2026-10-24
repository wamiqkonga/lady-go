package com.ladygo.bookings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingEvent;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingEventRepository;
import com.ladygo.common.domain.ActorType;
import com.ladygo.common.exception.BookingStateException;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.locations.domain.City;
import com.ladygo.testsupport.PersistenceTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;

class BookingStateMachineIntegrationTest extends PersistenceTestSupport {

    @Autowired
    private BookingStateMachine stateMachine;
    @Autowired
    private BookingEventRepository bookingEvents;
    @Autowired
    private RecordingListener recordingListener;

    @TestConfiguration
    static class RecordingListenerConfig {
        @Bean
        RecordingListener recordingListener() {
            return new RecordingListener();
        }
    }

    static class RecordingListener {
        final List<BookingStatusChangedEvent> received = new CopyOnWriteArrayList<>();

        @EventListener
        void onBookingStatusChanged(BookingStatusChangedEvent event) {
            received.add(event);
        }
    }

    private Booking requestedBooking() {
        Customer customer = newCustomer();
        City city = newCity();
        return newBooking(customer, city, BookingStatus.REQUESTED, null);
    }

    @Test
    void validTransitionPersistsStatusAndEvent() {
        Booking booking = requestedBooking();
        recordingListener.received.clear();

        Booking updated = stateMachine.transition(
                booking.getId(), BookingStatus.SEARCHING_DRIVER, ActorType.SYSTEM, null, "awaiting_matching");

        assertThat(updated.getStatus()).isEqualTo(BookingStatus.SEARCHING_DRIVER);
        List<BookingEvent> events = bookingEvents.findByBookingIdOrderByCreatedAtAsc(booking.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getFromStatus()).isEqualTo(BookingStatus.REQUESTED);
        assertThat(events.get(0).getToStatus()).isEqualTo(BookingStatus.SEARCHING_DRIVER);
        assertThat(events.get(0).getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(recordingListener.received)
                .anySatisfy(e -> assertThat(e.bookingId()).isEqualTo(booking.getId()));
    }

    @Test
    void invalidTransitionIsRejected() {
        Booking booking = requestedBooking();
        // REQUESTED cannot jump straight to RIDE_STARTED.
        assertThatThrownBy(() -> stateMachine.transition(
                booking.getId(), BookingStatus.RIDE_STARTED, ActorType.SYSTEM, null, "skip"))
                .isInstanceOf(BookingStateException.class);
    }

    @Test
    void customerCanCancelFromAnAllowedPreRideState() {
        Booking booking = requestedBooking();
        stateMachine.transition(booking.getId(), BookingStatus.SEARCHING_DRIVER, ActorType.SYSTEM, null, "go");

        Booking cancelled = stateMachine.transition(
                booking.getId(), BookingStatus.CANCELLED, ActorType.CUSTOMER, booking.getCustomerId(), "changed my mind");

        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        // ck_bookings_cancelled requires this on the bookings row itself, not just the event - a real
        // bug caught here: the state machine used to only record it in booking_events.
        assertThat(cancelled.getCancelledBy()).isEqualTo(ActorType.CUSTOMER);
        assertThat(cancelled.getCancelReason()).isEqualTo("changed my mind");
    }

    @Test
    void customerCannotDriveAnyNonCancelTransition() {
        Booking booking = requestedBooking();
        assertThatThrownBy(() -> stateMachine.transition(
                booking.getId(), BookingStatus.SEARCHING_DRIVER, ActorType.CUSTOMER, booking.getCustomerId(), "nope"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void rideStartedHasNoCancelledEdge() {
        UUID driverId = newOnlineDriver(newCity(), 74.80, 34.08).getId();
        Customer customer = newCustomer();
        Booking booking = newBooking(customer, newCity(), BookingStatus.DRIVER_ARRIVED, driverId);

        stateMachine.transition(booking.getId(), BookingStatus.RIDE_STARTED, ActorType.SYSTEM, null, "pin verified");

        assertThatThrownBy(() -> stateMachine.transition(
                booking.getId(), BookingStatus.CANCELLED, ActorType.CUSTOMER, customer.getId(), "too late"))
                .isInstanceOf(BookingStateException.class);
    }

    @Test
    void metadataIsStoredOnTheEvent() {
        Booking booking = requestedBooking();
        stateMachine.transition(booking.getId(), BookingStatus.SEARCHING_DRIVER, ActorType.SYSTEM, null, "go");

        stateMachine.transition(booking.getId(), BookingStatus.CANCELLED, ActorType.CUSTOMER, booking.getCustomerId(),
                "cancelled", Map.of("cancellationFee", 5000, "currency", "INR"));

        BookingEvent cancelEvent = bookingEvents.findByBookingIdOrderByCreatedAtAsc(booking.getId()).stream()
                .filter(e -> e.getToStatus() == BookingStatus.CANCELLED)
                .findFirst().orElseThrow();
        assertThat(cancelEvent.getMetadata()).containsEntry("currency", "INR");
    }

    @Test
    void unknownBookingIsNotFound() {
        assertThatThrownBy(() -> stateMachine.transition(
                UUID.randomUUID(), BookingStatus.CANCELLED, ActorType.CUSTOMER, null, "x"))
                .isInstanceOf(com.ladygo.common.exception.ResourceNotFoundException.class);
    }
}
