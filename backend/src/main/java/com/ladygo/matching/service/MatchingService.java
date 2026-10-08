package com.ladygo.matching.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.domain.OfferStatus;
import com.ladygo.bookings.domain.RideOffer;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.bookings.repository.RideOfferRepository;
import com.ladygo.bookings.service.BookingField;
import com.ladygo.bookings.service.BookingService;
import com.ladygo.bookings.service.BookingStateMachine;
import com.ladygo.bookings.service.BookingStatusChangedEvent;
import com.ladygo.bookings.service.BookingVisibilityPolicy;
import com.ladygo.bookings.service.BookingViewerRole;
import com.ladygo.bookings.web.dto.BookingDetailResponse;
import com.ladygo.common.domain.ActorType;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.common.geo.GeoPoints;
import com.ladygo.config.ConfigurationService;
import com.ladygo.customers.domain.Customer;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.matching.web.dto.OfferResponse;
import com.ladygo.realtime.RealtimePushService;
import com.ladygo.vehicles.domain.VehicleVerificationStatus;
import com.ladygo.vehicles.repository.VehicleRepository;
import org.locationtech.jts.geom.Point;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * docs/api/matching.md sections 4-6: Postgres-only nearby search and offer state (the existing
 * {@code ride_offers} unique indexes are the only concurrency guard needed - no Redis lock). One offer
 * in flight per booking at a time, sequential candidates, atomic claim via a conditional UPDATE on
 * {@code drivers.availability_status} (same idiom as the ride-selfie claim).
 */
@Service
public class MatchingService {

    private final BookingRepository bookings;
    private final DriverRepository drivers;
    private final VehicleRepository vehicles;
    private final CustomerRepository customers;
    private final RideOfferRepository offers;
    private final BookingStateMachine stateMachine;
    private final BookingService bookingService;
    private final ConfigurationService config;
    private final RealtimePushService push;

    public MatchingService(
            BookingRepository bookings, DriverRepository drivers, VehicleRepository vehicles,
            CustomerRepository customers, RideOfferRepository offers, BookingStateMachine stateMachine,
            BookingService bookingService, ConfigurationService config, RealtimePushService push) {
        this.bookings = bookings;
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.customers = customers;
        this.offers = offers;
        this.stateMachine = stateMachine;
        this.bookingService = bookingService;
        this.config = config;
        this.push = push;
    }

    @EventListener
    public void onBookingStatusChanged(BookingStatusChangedEvent event) {
        if (event.to() == BookingStatus.SEARCHING_DRIVER) {
            tryOfferNextCandidate(event.bookingId());
        }
    }

    /** Idempotent/safe to call repeatedly (the event listener fires it immediately; the sweep job
     *  fires it again on retry) - a booking not in SEARCHING_DRIVER, or already holding a live offer,
     *  is a no-op. */
    @Transactional
    public void tryOfferNextCandidate(UUID bookingId) {
        Booking booking = bookings.findById(bookingId).orElse(null);
        if (booking == null || booking.getStatus() != BookingStatus.SEARCHING_DRIVER) {
            return;
        }
        if (offers.findByBookingIdAndStatus(bookingId, OfferStatus.OFFERED).isPresent()) {
            return;
        }
        List<RideOffer> existingOffers = offers.findByBookingId(bookingId);
        int maxOffers = config.getInt("matching.max.offers", 10);
        if (existingOffers.size() >= maxOffers) {
            return; // OfferSweepJob's exhaustion check cancels this booking.
        }
        Set<UUID> alreadyOffered = existingOffers.stream().map(RideOffer::getDriverId).collect(Collectors.toSet());

        double radiusM = config.getInt("driver.search.radius.m", 5000);
        Point pickup = booking.getPickupLocation();
        List<Driver> candidates = drivers.findNearbyOnline(
                booking.getCityId(), pickup.getX(), pickup.getY(), radiusM, Math.max(maxOffers * 3, 10));

        for (Driver candidate : candidates) {
            if (alreadyOffered.contains(candidate.getId())) {
                continue;
            }
            boolean hasMatchingApprovedVehicle = vehicles.findByDriverId(candidate.getId()).stream().anyMatch(
                    v -> v.getVerificationStatus() == VehicleVerificationStatus.APPROVED
                            && v.getVehicleType() == booking.getVehicleType());
            if (!hasMatchingApprovedVehicle) {
                continue;
            }
            if (drivers.claimForOffer(candidate.getId()) == 0) {
                continue; // raced by another booking's search - try the next candidate.
            }
            offerTo(booking, candidate);
            return; // one offer in flight at a time.
        }
    }

    private void offerTo(Booking booking, Driver driver) {
        RideOffer offer = new RideOffer();
        offer.setBookingId(booking.getId());
        offer.setDriverId(driver.getId());
        offer.setDistanceM((int) GeoPoints.haversineMeters(driver.getCurrentLocation(), booking.getPickupLocation()));
        Duration responseTimeout = config.getSeconds("driver.response.timeout.s", Duration.ofSeconds(20));
        offer.setExpiresAt(Instant.now().plus(responseTimeout));
        RideOffer saved = offers.saveAndFlush(offer);

        booking.setDriverId(driver.getId());
        bookings.save(booking);
        stateMachine.transition(booking.getId(), BookingStatus.DRIVER_ASSIGNED, ActorType.SYSTEM, null, "offered");

        push.pushToUser(driver.getUserId(), "/queue/offers", toOfferResponse(saved, booking));
    }

    @Transactional
    public BookingDetailResponse accept(UUID driverUserId, UUID offerId) {
        Driver driver = resolveDriver(driverUserId);
        RideOffer offer = liveOfferOwnedBy(offerId, driver.getId());

        offer.setStatus(OfferStatus.ACCEPTED);
        offer.setRespondedAt(Instant.now());
        offers.save(offer);

        Booking accepted = stateMachine.transition(
                offer.getBookingId(), BookingStatus.DRIVER_ACCEPTED, ActorType.DRIVER, driver.getId(), "accepted");
        pushBookingUpdateToCustomer(accepted);
        return bookingService.getForDriver(driverUserId, offer.getBookingId());
    }

    @Transactional
    public void reject(UUID driverUserId, UUID offerId, String reason) {
        Driver driver = resolveDriver(driverUserId);
        RideOffer offer = liveOfferOwnedBy(offerId, driver.getId());

        offer.setStatus(OfferStatus.REJECTED);
        offer.setRespondedAt(Instant.now());
        offers.save(offer);
        drivers.releaseFromOffer(driver.getId());

        Booking booking = bookings.findById(offer.getBookingId()).orElseThrow(ResourceNotFoundException::new);
        booking.setDriverId(null);
        bookings.save(booking);
        stateMachine.transition(offer.getBookingId(), BookingStatus.SEARCHING_DRIVER, ActorType.DRIVER,
                driver.getId(), reason == null || reason.isBlank() ? "rejected" : reason);

        tryOfferNextCandidate(offer.getBookingId());
    }

    /** HTTP polling fallback for offer delivery (docs/architecture/realtime-architecture.md section 6) -
     *  not relying solely on the WebSocket push arriving. */
    public OfferResponse getCurrentOffer(UUID driverUserId) {
        Driver driver = resolveDriver(driverUserId);
        RideOffer offer = offers.findByDriverIdAndStatus(driver.getId(), OfferStatus.OFFERED)
                .filter(o -> o.getExpiresAt() == null || o.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(ResourceNotFoundException::new);
        Booking booking = bookings.findById(offer.getBookingId()).orElseThrow(ResourceNotFoundException::new);
        return toOfferResponse(offer, booking);
    }

    private RideOffer liveOfferOwnedBy(UUID offerId, UUID driverId) {
        RideOffer offer = offers.findById(offerId)
                .filter(o -> driverId.equals(o.getDriverId()))
                .orElseThrow(ResourceNotFoundException::new);
        if (offer.getStatus() != OfferStatus.OFFERED) {
            throw new ValidationException("This offer is no longer available.");
        }
        if (offer.getExpiresAt() != null && offer.getExpiresAt().isBefore(Instant.now())) {
            throw new ValidationException("This offer has expired.");
        }
        return offer;
    }

    private void pushBookingUpdateToCustomer(Booking booking) {
        Customer customer = customers.findById(booking.getCustomerId()).orElse(null);
        if (customer != null) {
            push.pushToUser(customer.getUserId(), "/queue/booking", bookingService.get(customer.getUserId(), booking.getId()));
        }
    }

    private Driver resolveDriver(UUID userId) {
        return drivers.findByUserId(userId).orElseThrow(ResourceNotFoundException::new);
    }

    /** Same {@link BookingVisibilityPolicy} every other booking/offer/selfie read path uses - the
     *  approximate point is only ever included because the policy grants
     *  {@link BookingField#PICKUP_LOCATION_APPROXIMATE} for an OFFERED, not-yet-accepted driver; it
     *  never includes the exact address (that field is simply absent from {@link OfferResponse}). */
    private OfferResponse toOfferResponse(RideOffer offer, Booking booking) {
        boolean approximateVisible = BookingVisibilityPolicy.isVisible(
                BookingViewerRole.DRIVER, booking.getStatus(), BookingField.PICKUP_LOCATION_APPROXIMATE);
        if (!approximateVisible) {
            return new OfferResponse(offer.getId(), booking.getId(), booking.getVehicleType(),
                    null, null, booking.getEstimatedFare(), booking.getCurrency(),
                    offer.getDistanceM(), offer.getExpiresAt());
        }
        int approxRadiusM = config.getInt("booking.pickup.approximate.radius.m", 300);
        Point approx = GeoPoints.roundToGrid(booking.getPickupLocation(), approxRadiusM);
        return new OfferResponse(offer.getId(), booking.getId(), booking.getVehicleType(),
                approx.getY(), approx.getX(), booking.getEstimatedFare(), booking.getCurrency(),
                offer.getDistanceM(), offer.getExpiresAt());
    }
}
