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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** docs/api/matching.md section 3. Staleness is judged from the SERVER's own receive time, never the
 *  device's clock - see {@link DriverLocationService}'s class javadoc for why. */
class DriverLocationServiceTest extends PersistenceTestSupport {

    @Autowired
    private DriverLocationService service;

    private Driver onlineDriver(City city) {
        Driver driver = newDriver(city);
        driver.setVerificationStatus(com.ladygo.drivers.domain.DriverVerificationStatus.APPROVED);
        driver.setAvailabilityStatus(DriverAvailability.ONLINE);
        return drivers.saveAndFlush(driver);
    }

    /** Two or more real calls in quick succession, needed to compare against the driver's previous
     *  point - loosen the per-driver throttle first so the rate limiter (tested on its own below)
     *  doesn't mask the speed check these tests are actually about. */
    private void loosenLocationRateLimit() {
        jdbc.update("UPDATE configuration SET value = '100' WHERE key = 'driver.location.rate.max.attempts'");
    }

    @Test
    void aValidUpdatePersistsTheDriversCurrentLocation() {
        Driver driver = onlineDriver(newCity());

        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, Instant.now(), 5.0));

        Driver reloaded = drivers.findById(driver.getId()).orElseThrow();
        assertThat(reloaded.getCurrentLocation().getY()).isEqualTo(34.08);
        assertThat(reloaded.getCurrentLocation().getX()).isEqualTo(74.80);
    }

    @Test
    void anOfflineDriverCannotReportLocation() {
        Driver driver = newDriver(newCity()); // OFFLINE by default

        assertThatThrownBy(() -> service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(34.08, 74.80, Instant.now(), 5.0)))
                .isInstanceOf(ValidationException.class);
    }

    /** docs/api/ride-lifecycle.md section 13b-2: mandatory in the API contract outside the dev-only
     *  bypass - this test runs under the "test" profile, where the bypass is off by default. */
    @Test
    void aMissingAccuracyIsRejectedByDefault() {
        Driver driver = onlineDriver(newCity());

        assertThatThrownBy(() -> service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(34.08, 74.80, Instant.now(), null)))
                .isInstanceOf(ValidationException.class);
    }

    /** There is nothing to compare a FIRST update against yet, so an old or future device timestamp on
     *  it alone is accepted without complaint - staleness only ever matters once there's a previous
     *  point to judge elapsed time against, and that comparison uses server time, not this value. */
    @Test
    void aFirstUpdateIsAcceptedRegardlessOfHowOldOrFutureItsOwnDeviceTimestampIs() {
        Driver driver = onlineDriver(newCity());

        service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(34.08, 74.80, Instant.now().minus(1, ChronoUnit.HOURS), 5.0));

        assertThat(drivers.findById(driver.getId()).orElseThrow().getCurrentLocation().getY()).isEqualTo(34.08);
    }

    /** ~650km in under a minute - no real vehicle does that; location-spoofing defence. */
    @Test
    void anImpossibleSpeedJumpIsRejected() {
        loosenLocationRateLimit();
        Driver driver = onlineDriver(newCity());
        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, Instant.now(), 5.0));

        assertThatThrownBy(() -> service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(40.0, 74.80, Instant.now().plusSeconds(30), 5.0)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aPlausibleFollowUpUpdateIsAccepted() {
        loosenLocationRateLimit();
        Driver driver = onlineDriver(newCity());
        Instant first = Instant.now();
        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, first, 5.0));

        // ~110m away, 3s later - a perfectly normal driving speed.
        service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(34.081, 74.80, first.plusSeconds(3), 5.0));

        assertThat(drivers.findById(driver.getId()).orElseThrow().getCurrentLocation().getY()).isEqualTo(34.081);
    }

    /** Adversarial review finding, MEDIUM: an out-of-order/duplicate DEVICE timestamp previously
     *  SKIPPED the speed check entirely (and still applied the point) instead of being rejected -
     *  letting an attacker bypass teleport detection just by sending a non-increasing timestamp. */
    @Test
    void anOutOfOrderDeviceTimestampIsRejectedRatherThanSkippingValidation() {
        loosenLocationRateLimit();
        Driver driver = onlineDriver(newCity());
        Instant first = Instant.now();
        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, first, 5.0));

        assertThatThrownBy(() -> service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(40.0, 74.80, first, 5.0))) // same device timestamp, huge jump
                .isInstanceOf(ValidationException.class);
        // the teleporting point must never have been applied.
        assertThat(drivers.findById(driver.getId()).orElseThrow().getCurrentLocation().getY()).isEqualTo(34.08);
    }

    /** The user's own correction: a device clock that's simply wrong (here, 10 minutes behind real
     *  time) must not break the speed check - the two updates' RELATIVE device-time delta (3s apart,
     *  by the SAME skewed clock) is what matters, never a comparison against server "now". */
    @Test
    void aSkewedDeviceClockStillAllowsAPlausibleFollowUpUpdate() {
        loosenLocationRateLimit();
        Driver driver = onlineDriver(newCity());
        Instant skewed = Instant.now().minus(10, ChronoUnit.MINUTES);
        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, skewed, 5.0));

        service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(34.081, 74.80, skewed.plusSeconds(3), 5.0));

        assertThat(drivers.findById(driver.getId()).orElseThrow().getCurrentLocation().getY()).isEqualTo(34.081);
    }

    /** Same skewed clock, but this time the two buffered points imply an impossible speed between
     *  them - the teleport check must still catch it using the device's own relative delta, clock
     *  skew or not. */
    @Test
    void aSkewedDeviceClockStillCatchesATeleport() {
        loosenLocationRateLimit();
        Driver driver = onlineDriver(newCity());
        Instant skewed = Instant.now().minus(10, ChronoUnit.MINUTES);
        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, skewed, 5.0));

        assertThatThrownBy(() -> service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(40.0, 74.80, skewed.plusSeconds(30), 5.0)))
                .isInstanceOf(ValidationException.class);
    }

    /** Offline-then-reconnect: the driver's last SERVER-acknowledged update was long enough ago (by
     *  our own clock) that comparing against it would be meaningless - this first buffered point after
     *  reconnecting is correctly treated as a fresh start (no speed check), not rejected or wrongly
     *  flagged as a teleport, even though it implies a huge jump from the stale stored point. */
    @Test
    void theFirstUpdateAfterReconnectingPastMaxAgeSkipsTheSpeedCheck() {
        loosenLocationRateLimit();
        Driver driver = onlineDriver(newCity());
        service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(34.08, 74.80, Instant.now().minus(5, ChronoUnit.MINUTES), 5.0));
        flushAndClear(); // flush that call's write BEFORE backdating, or the backdate below gets
                          // silently overwritten when this flushes again afterward.

        // Simulate real time having passed while the driver was offline by backdating the stored
        // SERVER receive time directly - this is a server-clock fact, nothing to do with the device.
        jdbc.update("UPDATE drivers SET current_location_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(2, ChronoUnit.MINUTES)), driver.getId());
        flushAndClear(); // nothing pending now - just evicts the cached (pre-backdate) entity.

        // A huge implied jump if compared against the stale point, from a buffered point captured
        // while offline - accepted, because the gap since last SERVER contact exceeds max_age.
        service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(40.0, 74.80, Instant.now().minus(1, ChronoUnit.MINUTES), 5.0));

        assertThat(drivers.findById(driver.getId()).orElseThrow().getCurrentLocation().getY()).isEqualTo(40.0);
    }

    /** Once the FIRST point of a reconnect batch has been accepted, subsequent buffered points in that
     *  SAME batch arrive moments apart in real (server) time, so they DO get a real speed check against
     *  each other using their own (old, but internally consistent) device timestamps - a batch doesn't
     *  get a blanket pass just because it followed a gap. */
    @Test
    void subsequentPointsInTheSameReconnectBatchAreStillSpeedChecked() {
        loosenLocationRateLimit();
        Driver driver = onlineDriver(newCity());
        Instant bufferedBase = Instant.now().minus(2, ChronoUnit.MINUTES); // captured while offline
        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, bufferedBase, 5.0));

        // Sent moments later in real time (still part of the same reconnect burst) - within max_age of
        // the point just accepted, so this one IS compared, and the implied speed is impossible.
        assertThatThrownBy(() -> service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(40.0, 74.80, bufferedBase.plusSeconds(30), 5.0)))
                .isInstanceOf(ValidationException.class);
    }

    /** The same batch, but with a physically plausible gap between buffered points - accepted. */
    @Test
    void aPlausibleReconnectBatchIsAcceptedPointByPoint() {
        loosenLocationRateLimit();
        Driver driver = onlineDriver(newCity());
        Instant bufferedBase = Instant.now().minus(2, ChronoUnit.MINUTES);
        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, bufferedBase, 5.0));

        service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(34.081, 74.80, bufferedBase.plusSeconds(10), 5.0));

        assertThat(drivers.findById(driver.getId()).orElseThrow().getCurrentLocation().getY()).isEqualTo(34.081);
    }

    /** Security review finding, MEDIUM: without a cap, a malicious client could claim an inflated
     *  device-time gap (here, 10 minutes, while the server-side gap stays near zero - still well under
     *  max_age) to shrink the computed speed for a real ~10km jump down to an ordinary 60km/h. Capping
     *  the elapsed-time denominator at max_age forces the same jump to be judged against a 60s window
     *  instead (~600km/h), catching it. */
    @Test
    void anInflatedDeviceTimeGapCannotBeUsedToMaskATeleport() {
        loosenLocationRateLimit();
        Driver driver = onlineDriver(newCity());
        Instant first = Instant.now();
        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, first, 5.0));

        // ~10km away, claimed 10 minutes later by the DEVICE clock, but the server received both
        // calls moments apart - uncapped, 10km/600s = 60km/h would sail through the speed check.
        assertThatThrownBy(() -> service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(34.17, 74.80, first.plusSeconds(600), 5.0)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void repeatedUpdatesFasterThanTheThrottleAreRejected() {
        Driver driver = onlineDriver(newCity());
        Instant now = Instant.now();
        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, now, 5.0));

        assertThatThrownBy(() -> service.updateLocation(driver.getUserId(),
                new DriverLocationRequest(34.0801, 74.80, now.plusMillis(200), 5.0)))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void locationHistoryIsSampledOnlyDuringAnActiveRide() {
        City city = newCity();
        Driver driver = onlineDriver(city);
        Customer customer = newCustomer();
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        Ride ride = newRide(booking, driver);

        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, Instant.now(), 5.0));

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM location_history WHERE ride_id = ?", Integer.class, ride.getId());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void locationHistoryIsNotSampledWhileJustIdlyOnline() {
        Driver driver = onlineDriver(newCity());

        service.updateLocation(driver.getUserId(), new DriverLocationRequest(34.08, 74.80, Instant.now(), 5.0));

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM location_history WHERE driver_id = ?", Integer.class, driver.getId());
        assertThat(count).isZero();
    }
}
