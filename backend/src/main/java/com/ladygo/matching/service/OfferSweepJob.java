package com.ladygo.matching.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.domain.OfferStatus;
import com.ladygo.bookings.domain.RideOffer;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.bookings.repository.RideOfferRepository;
import com.ladygo.bookings.service.BookingStateMachine;
import com.ladygo.common.domain.ActorType;
import com.ladygo.config.ConfigurationService;
import com.ladygo.drivers.repository.DriverRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The DB-backed expiry/retry/exhaustion sweep (docs/api/matching.md section 4/5) - the system of
 * record for offer timeout, works whether or not the WebSocket/Redis push ever arrived. Expires a
 * stale OFFERED row, advances a booking with no live offer to the next candidate, and cancels a
 * booking that has exhausted its candidates or its search-time budget.
 */
@Component
public class OfferSweepJob {

    private final BookingRepository bookings;
    private final RideOfferRepository offers;
    private final DriverRepository drivers;
    private final BookingStateMachine stateMachine;
    private final MatchingService matchingService;
    private final ConfigurationService config;

    public OfferSweepJob(
            BookingRepository bookings, RideOfferRepository offers, DriverRepository drivers,
            BookingStateMachine stateMachine, MatchingService matchingService, ConfigurationService config) {
        this.bookings = bookings;
        this.offers = offers;
        this.drivers = drivers;
        this.stateMachine = stateMachine;
        this.matchingService = matchingService;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${ladygo.matching.sweep.interval-ms:3000}")
    @Transactional
    public void sweep() {
        expireStaleOffers();
        advanceOrCancelSearchingBookings();
    }

    private void expireStaleOffers() {
        for (RideOffer offer : offers.findByStatusAndExpiresAtBefore(OfferStatus.OFFERED, Instant.now())) {
            offer.setStatus(OfferStatus.EXPIRED);
            offer.setRespondedAt(Instant.now());
            offers.save(offer);
            drivers.releaseFromOffer(offer.getDriverId());

            bookings.findById(offer.getBookingId()).filter(b -> b.getStatus() == BookingStatus.DRIVER_ASSIGNED)
                    .ifPresent(booking -> {
                        booking.setDriverId(null);
                        bookings.save(booking);
                        stateMachine.transition(booking.getId(), BookingStatus.SEARCHING_DRIVER,
                                ActorType.SYSTEM, null, "offer_expired");
                    });
        }
    }

    private void advanceOrCancelSearchingBookings() {
        Duration maxSearchTime = config.getSeconds("ride.max.search.time.s", Duration.ofSeconds(180));
        int maxOffers = config.getInt("matching.max.offers", 10);

        for (Booking booking : bookings.findByStatus(BookingStatus.SEARCHING_DRIVER)) {
            if (offers.findByBookingIdAndStatus(booking.getId(), OfferStatus.OFFERED).isPresent()) {
                continue; // already has a live offer in flight.
            }
            boolean searchTimeExceeded = Instant.now().isAfter(booking.getCreatedAt().plus(maxSearchTime));
            boolean candidatesExhausted = offers.findByBookingId(booking.getId()).size() >= maxOffers;
            if (searchTimeExceeded || candidatesExhausted) {
                stateMachine.transition(booking.getId(), BookingStatus.CANCELLED, ActorType.SYSTEM,
                        null, "NO_DRIVER_FOUND", Map.of("cancellationFee", 0, "currency", booking.getCurrency()));
            } else {
                matchingService.tryOfferNextCandidate(booking.getId());
            }
        }
    }
}
