package com.ladygo.bookings.service;

import java.util.UUID;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingEvent;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingEventRepository;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.common.domain.ActorType;
import com.ladygo.verification.service.RideSelfieService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The actual insert, isolated in its own transaction (docs/api/booking.md section 2, B3). A separate
 * bean (not a method on {@link BookingService}) so a unique-constraint violation here rolls back only
 * this transaction - Postgres aborts the whole transaction on a constraint violation, so the caller's
 * transaction would otherwise be left unusable for the refetch-the-winning-row fallback. Claiming the
 * ride selfie (docs/api/driver-onboarding.md section 4) happens in this same transaction, right after
 * the booking gets a real ID, so a stale/already-used/not-this-customer's selfie rolls back the whole
 * booking insert too - not a committed booking with no valid selfie attached.
 */
@Service
public class BookingCreationTransaction {

    private final BookingRepository bookings;
    private final BookingEventRepository events;
    private final BookingStateMachine stateMachine;
    private final RideSelfieService rideSelfieService;

    public BookingCreationTransaction(
            BookingRepository bookings, BookingEventRepository events, BookingStateMachine stateMachine,
            RideSelfieService rideSelfieService) {
        this.bookings = bookings;
        this.events = events;
        this.stateMachine = stateMachine;
        this.rideSelfieService = rideSelfieService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Booking insert(Booking booking, UUID rideSelfieId) {
        Booking saved = bookings.saveAndFlush(booking); // forces the unique-constraint check now.
        rideSelfieService.claimForBooking(rideSelfieId, saved.getCustomerId(), saved.getId());

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
