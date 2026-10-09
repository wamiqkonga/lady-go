package com.ladygo.rides.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.locations.domain.City;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.domain.RideStatus;
import com.ladygo.rides.domain.TripShare;
import com.ladygo.rides.repository.TripShareRepository;
import com.ladygo.rides.web.dto.TripShareCreatedResponse;
import com.ladygo.rides.web.dto.TripShareLookupResponse;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.vehicles.domain.Vehicle;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** docs/api/safety.md section 4. */
class TripShareServiceTest extends PersistenceTestSupport {

    @Autowired
    private TripShareService service;
    @Autowired
    private TripShareRepository tripShares;

    private City city() {
        return newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
    }

    /** A driver with a name and vehicle set, positioned so the public lookup has something real to
     *  return. */
    private Driver driverWithVehicle(City city, String fullName) {
        Driver driver = newDriver(city);
        driver.setVerificationStatus(DriverVerificationStatus.APPROVED);
        driver.setCurrentLocation(point(74.81, 34.09));
        driver.setCurrentLocationAt(Instant.now());
        drivers.saveAndFlush(driver);
        User driverUser = users.findById(driver.getUserId()).orElseThrow();
        driverUser.setName(fullName);
        users.saveAndFlush(driverUser);
        Vehicle vehicle = newVehicle(driver);
        vehicle.setMake("Maruti");
        vehicle.setModel("Dzire");
        vehicle.setColor("White");
        vehicles.saveAndFlush(vehicle);
        return driver;
    }

    private Booking acceptedBooking(Customer customer, City city, Driver driver) {
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        newRide(booking, driver);
        return booking;
    }

    @Test
    void riderCanCreateAShareForTheirOwnRideAndTheRawTokenWorksForLookup() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = driverWithVehicle(city, "Asha Verma");
        Booking booking = acceptedBooking(customer, city, driver);

        TripShareCreatedResponse created = service.create(customer.getUserId(), booking.getId());
        assertThat(created.token()).isNotBlank();
        assertThat(created.expiresAt()).isAfter(Instant.now());

        TripShareLookupResponse lookup = service.resolve(created.token(), "203.0.113.5");
        assertThat(lookup.driverFirstName()).isEqualTo("Asha"); // first name only, never the full name.
        assertThat(lookup.vehicleMake()).isEqualTo("Maruti");
        assertThat(lookup.vehicleModel()).isEqualTo("Dzire");
        assertThat(lookup.vehicleRegistration()).isNotBlank();
        assertThat(lookup.latitude()).isEqualTo(34.09);
        assertThat(lookup.longitude()).isEqualTo(74.81);
    }

    /** Security requirement: rider-only creation - a driver (or anyone else) calling create for a ride
     *  that isn't theirs as a CUSTOMER must be rejected, not silently scoped to someone else's ride. */
    @Test
    void creationByACustomerWhoIsNotThisBookingsRiderIsRejected() {
        City city = city();
        Customer owner = newCustomer();
        Customer impostor = newCustomer();
        Driver driver = driverWithVehicle(city, "Asha Verma");
        Booking booking = acceptedBooking(owner, city, driver);

        assertThatThrownBy(() -> service.create(impostor.getUserId(), booking.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void revokedShareNoLongerResolves() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = driverWithVehicle(city, "Asha Verma");
        Booking booking = acceptedBooking(customer, city, driver);
        TripShareCreatedResponse created = service.create(customer.getUserId(), booking.getId());
        TripShare share = tripShares.findAll().stream()
                .filter(s -> s.getId().equals(created.id())).findFirst().orElseThrow();

        service.revoke(customer.getUserId(), booking.getId(), share.getId());

        assertThatThrownBy(() -> service.resolve(created.token(), "203.0.113.5"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** Security requirement: revoke is also rider-only/ownership-scoped - someone else's share id
     *  can't be revoked by calling revoke with a mismatched booking/customer. */
    @Test
    void revokingAnotherRidersShareIsRejected() {
        City city = city();
        Customer owner = newCustomer();
        Customer impostor = newCustomer();
        Driver driver = driverWithVehicle(city, "Asha Verma");
        Booking booking = acceptedBooking(owner, city, driver);
        TripShareCreatedResponse created = service.create(owner.getUserId(), booking.getId());
        TripShare share = tripShares.findAll().stream()
                .filter(s -> s.getId().equals(created.id())).findFirst().orElseThrow();

        assertThatThrownBy(() -> service.revoke(impostor.getUserId(), booking.getId(), share.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        // Still resolvable - the rejected revoke attempt must not have mutated anything.
        assertThat(service.resolve(created.token(), "203.0.113.5")).isNotNull();
    }

    @Test
    void anUnknownTokenAndARevokedTokenProduceTheIdenticalError() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = driverWithVehicle(city, "Asha Verma");
        Booking booking = acceptedBooking(customer, city, driver);
        TripShareCreatedResponse created = service.create(customer.getUserId(), booking.getId());
        TripShare share = tripShares.findAll().stream()
                .filter(s -> s.getId().equals(created.id())).findFirst().orElseThrow();
        service.revoke(customer.getUserId(), booking.getId(), share.getId());

        Throwable unknownTokenError = catchThrowable(() -> service.resolve("not-a-real-token", "203.0.113.5"));
        Throwable revokedTokenError = catchThrowable(() -> service.resolve(created.token(), "203.0.113.5"));

        assertThat(unknownTokenError).isInstanceOf(ResourceNotFoundException.class);
        assertThat(revokedTokenError).isInstanceOf(ResourceNotFoundException.class);
        assertThat(unknownTokenError.getClass()).isEqualTo(revokedTokenError.getClass());
        assertThat(unknownTokenError.getMessage()).isEqualTo(revokedTokenError.getMessage());
    }

    private static Throwable catchThrowable(Runnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @Test
    void anExpiredShareIsRejectedTheSameWayAsAnUnknownOne() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = driverWithVehicle(city, "Asha Verma");
        Booking booking = acceptedBooking(customer, city, driver);
        TripShareCreatedResponse created = service.create(customer.getUserId(), booking.getId());
        flushAndClear(); // the create() call's own INSERT must land before the raw-SQL backdate below.
        jdbc.update("UPDATE trip_shares SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), created.id());
        flushAndClear(); // nothing pending now - just evicts the cached (pre-backdate) entity.

        assertThatThrownBy(() -> service.resolve(created.token(), "203.0.113.5"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** docs/api/safety.md section 4 - expiry shortly after ride end. A share created with a generous
     *  creation-time TTL must still stop working soon after the ride actually completes. */
    @Test
    void aShareStopsWorkingShortlyAfterTheRideCompletesEvenIfItsStoredExpiryIsFarAway() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = driverWithVehicle(city, "Asha Verma");
        Booking booking = acceptedBooking(customer, city, driver);
        TripShareCreatedResponse created = service.create(customer.getUserId(), booking.getId());
        // Stored expiry is still far in the future (the 12h default ceiling) - only the ride's own
        // completion + grace period should cut this off.
        assertThat(created.expiresAt()).isAfter(Instant.now().plus(1, ChronoUnit.HOURS));

        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();
        ride.setStatus(RideStatus.COMPLETED);
        ride.setCompletedAt(Instant.now().minus(1, ChronoUnit.HOURS)); // completed well past the 30-min grace.
        rides.saveAndFlush(ride);

        assertThatThrownBy(() -> service.resolve(created.token(), "203.0.113.5"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** The other half of the same behaviour: still within the post-ride grace window, it must still work. */
    @Test
    void aShareStillWorksWithinThePostRideGraceWindow() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = driverWithVehicle(city, "Asha Verma");
        Booking booking = acceptedBooking(customer, city, driver);
        TripShareCreatedResponse created = service.create(customer.getUserId(), booking.getId());

        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();
        ride.setStatus(RideStatus.COMPLETED);
        ride.setCompletedAt(Instant.now().minus(5, ChronoUnit.MINUTES)); // well within the 30-min grace.
        rides.saveAndFlush(ride);

        assertThat(service.resolve(created.token(), "203.0.113.5")).isNotNull();
    }

    @Test
    void theActiveLinkCapPerRideIsEnforced() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = driverWithVehicle(city, "Asha Verma");
        Booking booking = acceptedBooking(customer, city, driver);

        service.create(customer.getUserId(), booking.getId());
        service.create(customer.getUserId(), booking.getId());
        service.create(customer.getUserId(), booking.getId()); // default cap is 3.

        assertThatThrownBy(() -> service.create(customer.getUserId(), booking.getId()))
                .isInstanceOf(ValidationException.class);
    }

    /** Revoking one frees a slot under the cap. */
    @Test
    void revokingAShareFreesACapSlot() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = driverWithVehicle(city, "Asha Verma");
        Booking booking = acceptedBooking(customer, city, driver);

        TripShareCreatedResponse first = service.create(customer.getUserId(), booking.getId());
        service.create(customer.getUserId(), booking.getId());
        service.create(customer.getUserId(), booking.getId());
        service.revoke(customer.getUserId(), booking.getId(), first.id());

        assertThat(service.create(customer.getUserId(), booking.getId())).isNotNull();
    }

    @Test
    void perTokenLookupRateLimitIsEnforced() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = driverWithVehicle(city, "Asha Verma");
        Booking booking = acceptedBooking(customer, city, driver);
        TripShareCreatedResponse created = service.create(customer.getUserId(), booking.getId());

        for (int i = 0; i < 10; i++) {
            service.resolve(created.token(), "203.0.113." + i); // different IPs - isolates the per-token limit.
        }

        assertThatThrownBy(() -> service.resolve(created.token(), "203.0.113.99"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void perIpLookupRateLimitIsEnforcedAcrossDifferentTokens() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = driverWithVehicle(city, "Asha Verma");
        Booking booking = acceptedBooking(customer, city, driver);
        String sameIp = "203.0.113.42";

        for (int i = 0; i < 30; i++) {
            TripShareCreatedResponse created = service.create(customer.getUserId(), booking.getId());
            service.revoke(customer.getUserId(), booking.getId(), created.id()); // avoid the active-link cap.
            // A revoked token still consumes the per-token/IP lookup budget on an attempted resolve,
            // even though the lookup itself is rejected as not-found - the rate limiter runs first.
            catchThrowable(() -> service.resolve(created.token(), sameIp));
        }

        TripShareCreatedResponse oneMore = service.create(customer.getUserId(), booking.getId());
        assertThatThrownBy(() -> service.resolve(oneMore.token(), sameIp))
                .isInstanceOf(RateLimitExceededException.class);
    }
}
