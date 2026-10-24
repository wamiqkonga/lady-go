package com.ladygo.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingEvent;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.domain.OfferStatus;
import com.ladygo.bookings.domain.RideOffer;
import com.ladygo.bookings.repository.BookingEventRepository;
import com.ladygo.bookings.repository.RideOfferRepository;
import com.ladygo.common.domain.ActorType;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.locations.domain.City;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.payments.domain.PaymentTransaction;
import com.ladygo.payments.domain.PaymentWebhookEvent;
import com.ladygo.payments.repository.PaymentTransactionRepository;
import com.ladygo.payments.repository.PaymentWebhookEventRepository;
import com.ladygo.pricing.domain.PricingRule;
import com.ladygo.pricing.repository.PricingRuleRepository;
import com.ladygo.ratings.domain.RaterRole;
import com.ladygo.ratings.domain.Rating;
import com.ladygo.ratings.repository.RatingRepository;
import com.ladygo.rides.domain.Ride;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.vehicles.domain.VehicleType;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The database enforces the invariants that must hold even if application code has a bug or two workers race.
 * Each test proves one rule by trying to break it.
 */
class DatabaseConstraintsTest extends PersistenceTestSupport {

    @Autowired private BookingEventRepository bookingEvents;
    @Autowired private RideOfferRepository offers;
    @Autowired private PricingRuleRepository pricingRules;
    @Autowired private PaymentTransactionRepository payments;
    @Autowired private PaymentWebhookEventRepository webhooks;
    @Autowired private RatingRepository ratings;

    @Test
    void phoneMustBeE164() {
        User user = new User();
        user.setPhone("98765");
        user.setRole(UserRole.CUSTOMER);
        assertThatThrownBy(() -> users.saveAndFlush(user)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void phoneIsUnique() {
        User first = newUser(UserRole.CUSTOMER);
        User second = new User();
        second.setPhone(first.getPhone());
        second.setRole(UserRole.DRIVER);
        assertThatThrownBy(() -> users.saveAndFlush(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyAdminRolesCanHaveAPassword() {
        User user = new User();
        user.setPhone(nextPhone());
        user.setRole(UserRole.CUSTOMER);
        user.setPasswordHash("not-for-customers");
        assertThatThrownBy(() -> users.saveAndFlush(user)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void adminMayHaveAPassword() {
        User admin = new User();
        admin.setPhone(nextPhone());
        admin.setRole(UserRole.ADMIN);
        admin.setPasswordHash("argon2-hash");
        assertThat(users.saveAndFlush(admin).getId()).isNotNull();
    }

    @Test
    void onlyStaffRolesCanHaveAPendingMfaSecret() {
        User user = new User();
        user.setPhone(nextPhone());
        user.setRole(UserRole.CUSTOMER);
        user.setMfaPendingSecretEnc("not-for-customers");
        assertThatThrownBy(() -> users.saveAndFlush(user)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyStaffRolesCanBeForcedToChangePassword() {
        User user = new User();
        user.setPhone(nextPhone());
        user.setRole(UserRole.DRIVER);
        user.setMustChangePassword(true);
        assertThatThrownBy(() -> users.saveAndFlush(user)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void adminMayHaveAPendingMfaSecretAndBeForcedToChangePassword() {
        User admin = new User();
        admin.setPhone(nextPhone());
        admin.setRole(UserRole.SUPER_ADMIN);
        admin.setPasswordHash("argon2-hash");
        admin.setMfaPendingSecretEnc("pending-secret-ciphertext");
        admin.setMustChangePassword(true);
        assertThat(users.saveAndFlush(admin).getId()).isNotNull();
    }

    @Test
    void emailIsUniqueIgnoringCase() {
        User first = newUser(UserRole.CUSTOMER);
        first.setEmail("Rider@Example.com");
        users.saveAndFlush(first);
        User second = newUser(UserRole.CUSTOMER);
        second.setEmail("rider@example.com");
        assertThatThrownBy(() -> users.saveAndFlush(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unapprovedDriverCannotGoOnline() {
        Driver driver = newDriver(newCity());
        driver.setAvailabilityStatus(DriverAvailability.ONLINE);
        assertThatThrownBy(() -> drivers.saveAndFlush(driver)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void driverCannotHaveTwoAcceptedBookingsAtOnce() {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newOnlineDriver(city, 74.80, 34.08);
        newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());

        // A booking merely offered to the same driver is fine...
        newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());
        // ...but a second committed one is rejected by the database.
        assertThatThrownBy(() -> newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateIdempotencyKeyForTheSameCustomerIsRejected() {
        Customer customer = newCustomer();
        City city = newCity();
        Booking first = newBooking(customer, city, BookingStatus.REQUESTED, null);
        Booking second = newBooking(customer, city, BookingStatus.REQUESTED, null);
        second.setIdempotencyKey(first.getIdempotencyKey());
        assertThatThrownBy(() -> bookings.saveAndFlush(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameIdempotencyKeyIsFineAcrossDifferentCustomers() {
        City city = newCity();
        Booking first = newBooking(newCustomer(), city, BookingStatus.REQUESTED, null);
        Booking second = newBooking(newCustomer(), city, BookingStatus.REQUESTED, null);
        second.setIdempotencyKey(first.getIdempotencyKey());
        assertThat(bookings.saveAndFlush(second).getId()).isNotNull();
    }

    @Test
    void cancelledBookingMustSayWhoCancelled() {
        Booking booking = newBooking(newCustomer(), newCity(), BookingStatus.SEARCHING_DRIVER, null);
        booking.setStatus(BookingStatus.CANCELLED);
        assertThatThrownBy(() -> bookings.saveAndFlush(booking)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void bookingCanBeCancelledWhenActorIsRecorded() {
        Booking booking = newBooking(newCustomer(), newCity(), BookingStatus.SEARCHING_DRIVER, null);
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledBy(ActorType.CUSTOMER);
        assertThat(bookings.saveAndFlush(booking).getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void bookingCannotBeAcceptedByTwoDrivers() {
        City city = newCity();
        Booking booking = newBooking(newCustomer(), city, BookingStatus.SEARCHING_DRIVER, null);
        Driver first = newOnlineDriver(city, 74.80, 34.08);
        Driver second = newOnlineDriver(city, 74.81, 34.09);
        offers.saveAndFlush(offer(booking, first, OfferStatus.ACCEPTED));
        // A rejected or expired offer to another driver is fine, a second ACCEPTED is not.
        offers.saveAndFlush(offer(booking, second, OfferStatus.REJECTED));
        Driver third = newOnlineDriver(city, 74.82, 34.07);
        assertThatThrownBy(() -> offers.saveAndFlush(offer(booking, third, OfferStatus.ACCEPTED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static RideOffer offer(Booking booking, Driver driver, OfferStatus status) {
        RideOffer offer = new RideOffer();
        offer.setBookingId(booking.getId());
        offer.setDriverId(driver.getId());
        offer.setStatus(status);
        return offer;
    }

    @Test
    void overlappingPricingRulesAreRejected() {
        City city = newCity();
        pricingRules.saveAndFlush(rule(city, "2026-01-01T00:00:00Z", null));
        assertThatThrownBy(() -> pricingRules.saveAndFlush(rule(city, "2026-06-01T00:00:00Z", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pricingRuleCanBeSupersededByClosingTheOldOne() {
        City city = newCity();
        PricingRule old = pricingRules.saveAndFlush(rule(city, "2026-01-01T00:00:00Z", null));
        old.setEffectiveTo(Instant.parse("2026-06-01T00:00:00Z"));
        pricingRules.saveAndFlush(old);
        PricingRule next = pricingRules.saveAndFlush(rule(city, "2026-06-01T00:00:00Z", null));

        assertThat(pricingRules.findActive(city.getId(), VehicleType.CAB, Instant.parse("2026-03-01T00:00:00Z")))
                .map(PricingRule::getId).contains(old.getId());
        assertThat(pricingRules.findActive(city.getId(), VehicleType.CAB, Instant.parse("2026-09-01T00:00:00Z")))
                .map(PricingRule::getId).contains(next.getId());
        assertThat(pricingRules.findActive(city.getId(), VehicleType.CAB, Instant.parse("2025-12-31T00:00:00Z"))).isEmpty();
    }

    private static PricingRule rule(City city, String from, String to) {
        PricingRule rule = new PricingRule();
        rule.setCityId(city.getId());
        rule.setVehicleType(VehicleType.CAB);
        rule.setCurrency("INR");
        rule.setBaseFare(5000);
        rule.setPerKm(1200);
        rule.setPerMinute(200);
        rule.setMinimumFare(8000);
        rule.setEffectiveFrom(Instant.parse(from));
        rule.setEffectiveTo(to == null ? null : Instant.parse(to));
        return rule;
    }

    @Test
    void duplicatePaymentIdempotencyKeyIsRejected() {
        City city = newCity();
        Customer customer = newCustomer();
        Booking booking = newBooking(customer, city, BookingStatus.REQUESTED, null);
        payments.saveAndFlush(payment(booking, "idem-1"));
        assertThatThrownBy(() -> payments.saveAndFlush(payment(booking, "idem-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static PaymentTransaction payment(Booking booking, String key) {
        PaymentTransaction payment = new PaymentTransaction();
        payment.setBookingId(booking.getId());
        payment.setCustomerId(booking.getCustomerId());
        payment.setProvider("cash");
        payment.setMethod(PaymentMode.CASH);
        payment.setAmount(10000);
        payment.setCurrency("INR");
        payment.setIdempotencyKey(key);
        return payment;
    }

    @Test
    void replayedWebhookEventIsRejected() {
        webhooks.saveAndFlush(webhook("evt_1"));
        assertThat(webhooks.existsByProviderAndEventId("acme", "evt_1")).isTrue();
        assertThatThrownBy(() -> webhooks.saveAndFlush(webhook("evt_1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static PaymentWebhookEvent webhook(String eventId) {
        PaymentWebhookEvent event = new PaymentWebhookEvent();
        event.setProvider("acme");
        event.setEventId(eventId);
        event.setPayload(Map.of("type", "payment.succeeded"));
        return event;
    }

    @Test
    void ratingMustBeOneToFive() {
        Ride ride = rideWithParticipants();
        Rating rating = rating(ride, 6);
        assertThatThrownBy(() -> ratings.saveAndFlush(rating)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneRatingPerRaterPerRide() {
        Ride ride = rideWithParticipants();
        ratings.saveAndFlush(rating(ride, 5));
        assertThatThrownBy(() -> ratings.saveAndFlush(rating(ride, 4))).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Ride rideWithParticipants() {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newOnlineDriver(city, 74.80, 34.08);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        return newRide(booking, driver);
    }

    private Rating rating(Ride ride, int score) {
        Rating rating = new Rating();
        rating.setRideId(ride.getId());
        rating.setRaterId(customers.findById(ride.getCustomerId()).orElseThrow().getUserId());
        rating.setRateeId(drivers.findById(ride.getDriverId()).orElseThrow().getUserId());
        rating.setRaterRole(RaterRole.CUSTOMER);
        rating.setScore((short) score);
        return rating;
    }

    @Test
    void bookingEventsCannotBeEdited() {
        Booking booking = newBooking(newCustomer(), newCity(), BookingStatus.REQUESTED, null);
        BookingEvent event = new BookingEvent();
        event.setBookingId(booking.getId());
        event.setToStatus(BookingStatus.REQUESTED);
        event.setActorType(ActorType.SYSTEM);
        bookingEvents.saveAndFlush(event);

        assertThatThrownBy(() -> jdbc.update("UPDATE booking_events SET reason = 'tampered'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void auditLogsCannotBeEdited() {
        jdbc.update("INSERT INTO audit_logs (action) VALUES ('test.action')");
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_logs SET action = 'tampered'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void locationHistoryLandsInTheCurrentMonthPartitionAndOldDataInTheDefaultPartition() {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newOnlineDriver(city, 74.80, 34.08);
        Ride ride = newRide(newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId()), driver);

        String insert = "INSERT INTO location_history (ride_id, driver_id, location, recorded_at) "
                + "VALUES (?, ?, ST_GeogFromText('POINT(74.8 34.08)'), ?::timestamptz)";
        jdbc.update(insert, ride.getId(), driver.getId(), Instant.now().toString());
        jdbc.update(insert, ride.getId(), driver.getId(), "2020-01-01T00:00:00Z");

        String expectedPartition = jdbc.queryForObject(
                "SELECT 'location_history_' || to_char(now() AT TIME ZONE 'UTC', 'YYYY_MM')", String.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM location_history_default WHERE ride_id = ?", Integer.class, ride.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM " + expectedPartition + " WHERE ride_id = ?", Integer.class, ride.getId()))
                .isEqualTo(1);
    }
}
