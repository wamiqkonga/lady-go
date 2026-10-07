package com.ladygo.drivers.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.drivers.web.dto.DriverLocationRequest;
import com.ladygo.locations.domain.City;
import com.ladygo.rides.domain.Ride;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.testsupport.PersistenceTestSupport;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** docs/api/matching.md section 3. */
class DriverLocationServiceTest extends PersistenceTestSupport {

    @Autowired
    private DriverLocationService service;

    private Driver onlineDriver(City city) {
        Driver driver = newDriver(city);
        driver.setVerificationStatus(com.ladygo.drivers.domain.DriverVerificationStatus.APPROVED);
        driver.setAvailabilityStatus(DriverAvailability.ONLINE);
        return drivers.saveAndFlush(driver);
    }

    @Test
    void aValidUpdatePersistsTheDriversCurrentLocation() {
        Driver driver = onlineDriver(newCity());

        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, Instant.now()));

        Driver reloaded = drivers.findById(driver.getId()).orElseThrow();
        assertThat(reloaded.getCurrentLocation().getY()).isEqualTo(34.08);
        assertThat(reloaded.getCurrentLocation().getX()).isEqualTo(74.80);
    }

    @Test
    void anOfflineDriverCannotReportLocation() {
        Driver driver = newDriver(newCity()); // OFFLINE by default

        assertThatThrownBy(() -> service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(34.08, 74.80, Instant.now())))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aStaleTimestampIsRejected() {
        Driver driver = onlineDriver(newCity());

        assertThatThrownBy(() -> service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(34.08, 74.80, Instant.now().minus(1, ChronoUnit.HOURS))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aFutureTimestampIsRejected() {
        Driver driver = onlineDriver(newCity());

        assertThatThrownBy(() -> service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(34.08, 74.80, Instant.now().plus(1, ChronoUnit.HOURS))))
                .isInstanceOf(ValidationException.class);
    }

    /** Two real calls in quick succession, needed to compare against the driver's previous point -
     *  loosen the per-driver throttle first so the rate limiter (tested on its own below) doesn't mask
     *  the speed check this test is actually about. */
    private void loosenLocationRateLimit() {
        jdbc.update("UPDATE configuration SET value = '100' WHERE key = 'driver.location.rate.max.attempts'");
    }

    /** ~650km in under a minute - no real vehicle does that; location-spoofing defence. */
    @Test
    void anImpossibleSpeedJumpIsRejected() {
        loosenLocationRateLimit();
        Driver driver = onlineDriver(newCity());
        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, Instant.now()));

        assertThatThrownBy(() -> service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(40.0, 74.80, Instant.now().plusSeconds(30))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aPlausibleFollowUpUpdateIsAccepted() {
        loosenLocationRateLimit();
        Driver driver = onlineDriver(newCity());
        Instant first = Instant.now();
        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, first));

        // ~110m away, 3s later - a perfectly normal driving speed (kept within the future-skew
        // tolerance of real "now", since first was captured at real test-execution time too).
        service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(34.081, 74.80, first.plusSeconds(3)));

        assertThat(drivers.findById(driver.getId()).orElseThrow().getCurrentLocation().getY()).isEqualTo(34.081);
    }

    @Test
    void repeatedUpdatesFasterThanTheThrottleAreRejected() {
        Driver driver = onlineDriver(newCity());
        Instant now = Instant.now();
        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, now));

        assertThatThrownBy(() -> service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(34.0801, 74.80, now.plusMillis(200))))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void locationHistoryIsSampledOnlyDuringAnActiveRide() {
        City city = newCity();
        Driver driver = onlineDriver(city);
        Customer customer = newCustomer();
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        Ride ride = newRide(booking, driver);

        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, Instant.now()));

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM location_history WHERE ride_id = ?", Integer.class, ride.getId());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void locationHistoryIsNotSampledWhileJustIdlyOnline() {
        Driver driver = onlineDriver(newCity());

        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, Instant.now()));

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM location_history WHERE driver_id = ?", Integer.class, driver.getId());
        assertThat(count).isZero();
    }
}
