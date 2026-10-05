package com.ladygo.bookings.service;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingEvent;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingEventRepository;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.common.domain.ActorType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The actual insert, isolated in its own transaction (docs/api/booking.md section 2, B3). A separate
 * bean (not a method on {@link BookingService}) so a unique-constraint violation here rolls back only
 * this transaction - Postgres aborts the whole transaction on a constraint violation, so the caller's
 * transaction would otherwise be left unusable for the refetch-the-winning-row fallback.
 */
@Service
public class BookingCreationTransaction {

    private final BookingRepository bookings;
    private final BookingEventRepository events;
    private final BookingStateMachine stateMachine;

    public BookingCreationTransaction(
            BookingRepository bookings, BookingEventRepository events, BookingStateMachine stateMachine) {
        this.bookings = bookings;
        this.events = events;
        this.stateMachine = stateMachine;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Booking insert(Booking booking) {
        Booking saved = bookings.saveAndFlush(booking); // forces the unique-constraint check now.

        BookingEvent initial = new BookingEvent();
        initial.setBookingId(saved.getId());
        initial.setFromStatus(null);
        initial.setToStatus(BookingStatus.REQUESTED);
        initial.setActorType(ActorType.CUSTOMER);
        initial.setActorId(saved.getCustomerId());
        events.save(initial);

        return stateMachine.transition(
                saved.getId(), BookingStatus.SEARCHING_DRIVER, ActorType.SYSTEM, null, "awaiting_matching");
    }
}
