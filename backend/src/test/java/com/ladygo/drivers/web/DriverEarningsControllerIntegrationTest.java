package com.ladygo.drivers.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.common.domain.ActorType;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.repository.RideEarningRepository;
import com.ladygo.locations.domain.City;
import com.ladygo.ratings.domain.RaterRole;
import com.ladygo.ratings.domain.Rating;
import com.ladygo.ratings.repository.RatingRepository;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.domain.RideFlag;
import com.ladygo.rides.domain.RideFlagType;
import com.ladygo.rides.domain.RideStatus;
import com.ladygo.rides.repository.RideFlagRepository;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.RideScenarioSupport;
import com.ladygo.users.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class DriverEarningsControllerIntegrationTest extends RideScenarioSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private RideEarningRepository earnings;
    @Autowired private RatingRepository ratings;
    @Autowired private RideFlagRepository rideFlags;

    private String token(UUID userId, UserRole role) {
        return "Bearer " + jwtService.issueAccessToken(userId, role, Duration.ofMinutes(15));
    }

    private City city;
    private Customer customer;
    private Driver driver;

    private void setUpPeople() {
        city = newCity(); // Asia/Kolkata
        customer = newCustomer();
        driver = newDriver(city);
    }

    private Ride ride(Driver d, Instant completedAt) {
        return completedRide(city, customer, d, 10_000, completedAt);
    }

    // ---------------------------------------------------------------- history

    @Test
    void historyIsNewestFirstPaginatedAndLimitedToTheLastNinetyDays() throws Exception {
        setUpPeople();
        Ride newest = ride(driver, Instant.now().minus(Duration.ofHours(1)));
        Ride middle = ride(driver, Instant.now().minus(Duration.ofDays(30)));
        Ride oldestInWindow = ride(driver, Instant.now().minus(Duration.ofDays(89)));
        ride(driver, Instant.now().minus(Duration.ofDays(91))); // outside the window

        mockMvc.perform(get("/api/v1/drivers/me/rides?page=0&size=2")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].rideId").value(newest.getId().toString()))
                .andExpect(jsonPath("$.data.content[1].rideId").value(middle.getId().toString()))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2));

        mockMvc.perform(get("/api/v1/drivers/me/rides?page=1&size=2")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].rideId").value(oldestInWindow.getId().toString()));
    }

    @Test
    void historyShowsOnlyTheCallersOwnRides() throws Exception {
        setUpPeople();
        Driver other = newDriver(city);
        ride(driver, Instant.now());
        ride(other, Instant.now());

        mockMvc.perform(get("/api/v1/drivers/me/rides")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void historyCarriesNoRiderPersonalDataOrCoordinates() throws Exception {
        setUpPeople();
        ride(driver, Instant.now());
        var riderUser = users.findById(customer.getUserId()).orElseThrow();

        String body = mockMvc.perform(get("/api/v1/drivers/me/rides")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].pickupAddress").value("Lal Chowk"))
                .andExpect(jsonPath("$.data.content[0].destinationAddress").value("Hazratbal"))
                .andExpect(jsonPath("$.data.content[0].finalFare").value(10_000))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(riderUser.getPhone()).doesNotContain(riderUser.getName());
        assertThat(body.toLowerCase())
                .doesNotContain("selfie").doesNotContain("latitude").doesNotContain("longitude")
                .doesNotContain("customerid").doesNotContain("phone").doesNotContain("coordinates");
    }

    @Test
    void historyStatusesRatingsAndEarlyEnds() throws Exception {
        setUpPeople();
        Ride early = ride(driver, Instant.now().minus(Duration.ofMinutes(5)));
        RideFlag flag = new RideFlag();
        flag.setRideId(early.getId());
        flag.setBookingId(early.getBookingId());
        flag.setCustomerId(customer.getId());
        flag.setDriverId(driver.getId());
        flag.setType(RideFlagType.EARLY_END);
        flag.setRaisedBy(ActorType.DRIVER);
        rideFlags.saveAndFlush(flag);

        Ride rated = ride(driver, Instant.now().minus(Duration.ofMinutes(10)));
        Rating mine = new Rating();
        mine.setRideId(rated.getId());
        mine.setRaterId(driver.getUserId());
        mine.setRateeId(customer.getUserId());
        mine.setRaterRole(RaterRole.DRIVER);
        mine.setScore((short) 4);
        ratings.saveAndFlush(mine);
        Rating theirs = new Rating();
        theirs.setRideId(rated.getId());
        theirs.setRaterId(customer.getUserId());
        theirs.setRateeId(driver.getUserId());
        theirs.setRaterRole(RaterRole.CUSTOMER);
        theirs.setScore((short) 2);
        ratings.saveAndFlush(theirs);

        Booking cancelledBooking = newBooking(customer, city, BookingStatus.CANCELLED, driver.getId());
        Ride cancelled = newRide(cancelledBooking, driver);
        cancelled.setStatus(RideStatus.CANCELLED);
        cancelled.setCompletedAt(Instant.now().minus(Duration.ofMinutes(20)));
        rides.saveAndFlush(cancelled);
        // an unfinished ride must not appear at all
        newRide(newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, newDriver(city).getId()), newDriver(city));

        String body = mockMvc.perform(get("/api/v1/drivers/me/rides")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0].status").value("ENDED_EARLY"))
                .andExpect(jsonPath("$.data.content[1].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.content[1].myRating").value(4))
                .andExpect(jsonPath("$.data.content[1].ratedByRider").value(true))
                .andExpect(jsonPath("$.data.content[2].status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.content[2].myRating").doesNotExist())
                // a CANCELLED booking never shows exact addresses to the driver (BookingVisibilityPolicy)
                .andExpect(jsonPath("$.data.content[2].pickupAddress").doesNotExist())
                .andExpect(jsonPath("$.data.content[2].destinationAddress").doesNotExist())
                .andExpect(jsonPath("$.data.content[1].pickupAddress").value("Lal Chowk"))
                .andExpect(jsonPath("$.data.content[1].destinationAddress").value("Hazratbal"))
                .andReturn().getResponse().getContentAsString();
        // the rider's actual score (2) is never exposed to the driver as a per-ride value
        assertThat(body).doesNotContain("\"ratedByRider\":2");
    }

    @Test
    void aRiderSafetyCancelledRideShowsNoAddressesButACompletedOneDoes() throws Exception {
        setUpPeople();
        Ride completed = ride(driver, Instant.now().minus(Duration.ofMinutes(5)));
        Booking cancelledBooking = newBooking(customer, city, BookingStatus.CANCELLED, driver.getId());
        Ride cancelled = newRide(cancelledBooking, driver);
        cancelled.setStatus(RideStatus.CANCELLED);
        cancelled.setCompletedAt(Instant.now().minus(Duration.ofMinutes(1)));
        rides.saveAndFlush(cancelled);
        RideFlag flag = new RideFlag();
        flag.setRideId(cancelled.getId());
        flag.setBookingId(cancelledBooking.getId());
        flag.setCustomerId(customer.getId());
        flag.setDriverId(driver.getId());
        flag.setType(RideFlagType.RIDER_SAFETY_CANCEL);
        flag.setRaisedBy(ActorType.CUSTOMER);
        rideFlags.saveAndFlush(flag);

        mockMvc.perform(get("/api/v1/drivers/me/rides")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.content[0].pickupAddress").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].destinationAddress").doesNotExist())
                .andExpect(jsonPath("$.data.content[1].rideId").value(completed.getId().toString()))
                .andExpect(jsonPath("$.data.content[1].pickupAddress").value("Lal Chowk"))
                .andExpect(jsonPath("$.data.content[1].destinationAddress").value("Hazratbal"));
    }

    private void riderRated(Ride r) {
        Rating theirs = new Rating();
        theirs.setRideId(r.getId());
        theirs.setRaterId(customer.getUserId());
        theirs.setRateeId(driver.getUserId());
        theirs.setRaterRole(RaterRole.CUSTOMER);
        theirs.setScore((short) 1);
        ratings.saveAndFlush(theirs);
    }

    @Test
    void ratedByRiderStaysFalseUntilBothHaveRatedOrTheWindowHasClosed() throws Exception {
        setUpPeople();
        // newest first: fresh+rider only, fresh+both, old(window closed)+rider only, old+nobody
        Ride freshRiderOnly = ride(driver, Instant.now().minus(Duration.ofMinutes(1)));
        riderRated(freshRiderOnly);
        Ride freshBoth = ride(driver, Instant.now().minus(Duration.ofMinutes(2)));
        riderRated(freshBoth);
        Rating mine = new Rating();
        mine.setRideId(freshBoth.getId());
        mine.setRaterId(driver.getUserId());
        mine.setRateeId(customer.getUserId());
        mine.setRaterRole(RaterRole.DRIVER);
        mine.setScore((short) 5);
        ratings.saveAndFlush(mine);
        Ride oldRiderOnly = ride(driver, Instant.now().minus(Duration.ofDays(8)));
        riderRated(oldRiderOnly);
        ride(driver, Instant.now().minus(Duration.ofDays(9)));

        mockMvc.perform(get("/api/v1/drivers/me/rides")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].ratedByRider").value(false))
                .andExpect(jsonPath("$.data.content[1].ratedByRider").value(true))
                .andExpect(jsonPath("$.data.content[2].ratedByRider").value(true))
                .andExpect(jsonPath("$.data.content[3].ratedByRider").value(false));
    }

    @Test
    void historyIsDriverOnly() throws Exception {
        setUpPeople();
        mockMvc.perform(get("/api/v1/drivers/me/rides")
                        .header("Authorization", token(customer.getUserId(), UserRole.CUSTOMER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/drivers/me/rides")).andExpect(status().isUnauthorized());
    }

    // --------------------------------------------------------------- earnings

    private void earn(Driver d, Instant at, long gross, String method) {
        Ride r = ride(d, at);
        earnings.insertIfAbsent(r.getId(), d.getId(), gross, 0, 0, gross, "INR", method, at);
    }

    @Test
    void dayBucketsFollowTheCityTimeZoneNotUtc() throws Exception {
        setUpPeople();
        // Asia/Kolkata is UTC+05:30. 18:29Z on 1 Oct is 23:59 IST on 1 Oct; 18:30Z is 00:00 IST on 2 Oct.
        earn(driver, Instant.parse("2026-10-01T18:29:59Z"), 10_000, "CASH");
        earn(driver, Instant.parse("2026-10-01T18:30:00Z"), 4_000, "ONLINE");
        earn(driver, Instant.parse("2026-10-02T10:00:00Z"), 1_000, "CASH");
        earn(driver, Instant.parse("2026-10-03T18:30:00Z"), 9_999, "CASH"); // 4 Oct IST: outside the range

        mockMvc.perform(get("/api/v1/drivers/me/earnings?from=2026-10-01&to=2026-10-03")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timeZone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.data.days.length()").value(3))
                .andExpect(jsonPath("$.data.days[0].date").value("2026-10-01"))
                .andExpect(jsonPath("$.data.days[0].rides").value(1))
                .andExpect(jsonPath("$.data.days[0].gross").value(10_000))
                .andExpect(jsonPath("$.data.days[1].date").value("2026-10-02"))
                .andExpect(jsonPath("$.data.days[1].rides").value(2))
                .andExpect(jsonPath("$.data.days[1].gross").value(5_000))
                .andExpect(jsonPath("$.data.days[1].onlineCollected").value(4_000))
                .andExpect(jsonPath("$.data.days[1].cashCollected").value(1_000))
                .andExpect(jsonPath("$.data.days[2].rides").value(0))
                .andExpect(jsonPath("$.data.totals.rides").value(3))
                .andExpect(jsonPath("$.data.totals.gross").value(15_000))
                .andExpect(jsonPath("$.data.totals.net").value(15_000))
                .andExpect(jsonPath("$.data.totals.commission").value(0))
                .andExpect(jsonPath("$.data.totals.currency").value("INR"))
                .andExpect(jsonPath("$.data.totals.commissionConfigured").value(false));
    }

    @Test
    void commissionConfiguredAndCurrencyComeFromTheStoredRowsAcrossARateChange() throws Exception {
        setUpPeople();
        // Day 1 earned before any rate was set (bps 0); day 2 earned at 15% (USD rows prove the
        // currency is read from the rows, not the city); day 3 has no rows at all.
        Instant day1 = Instant.parse("2026-10-01T10:00:00Z");
        Instant day2 = Instant.parse("2026-10-02T10:00:00Z");
        Ride r1 = ride(driver, day1);
        earnings.insertIfAbsent(r1.getId(), driver.getId(), 10_000, 0, 0, 10_000, "USD", "CASH", day1);
        Ride r2 = ride(driver, day2);
        earnings.insertIfAbsent(r2.getId(), driver.getId(), 10_000, 1500, 1_500, 8_500, "USD", "CASH", day2);
        // the CURRENT config must not influence what history shows
        setConfig("earnings.commission.bps", "");

        mockMvc.perform(get("/api/v1/drivers/me/earnings?from=2026-10-01&to=2026-10-03")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days[0].commissionConfigured").value(false))
                .andExpect(jsonPath("$.data.days[0].currency").value("USD"))
                .andExpect(jsonPath("$.data.days[1].commissionConfigured").value(true))
                .andExpect(jsonPath("$.data.days[1].currency").value("USD"))
                .andExpect(jsonPath("$.data.days[2].commissionConfigured").value(false))
                .andExpect(jsonPath("$.data.days[2].currency").value("INR")) // empty bucket: city currency
                .andExpect(jsonPath("$.data.totals.commissionConfigured").value(true));

        // a range holding only the pre-rate day is not "configured", even though the config is set now
        setConfig("earnings.commission.bps", "1500");
        mockMvc.perform(get("/api/v1/drivers/me/earnings?from=2026-10-01&to=2026-10-01")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totals.commissionConfigured").value(false))
                .andExpect(jsonPath("$.data.totals.currency").value("USD"));
    }

    @Test
    void earningsDefaultToTheCurrentWeekAndOnlyCountTheCallersOwnRows() throws Exception {
        setUpPeople();
        Driver other = newDriver(city);
        earn(driver, Instant.now(), 2_000, "CASH");
        earn(other, Instant.now(), 7_000, "CASH");

        mockMvc.perform(get("/api/v1/drivers/me/earnings")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(7)))
                .andExpect(jsonPath("$.data.totals.gross").value(2_000));
    }

    @Test
    void invalidRangesAreRejected() throws Exception {
        setUpPeople();
        String auth = token(driver.getUserId(), UserRole.DRIVER);
        mockMvc.perform(get("/api/v1/drivers/me/earnings?from=2026-10-01&to=2026-11-01").header("Authorization", auth))
                .andExpect(status().isBadRequest()); // 32 days
        mockMvc.perform(get("/api/v1/drivers/me/earnings?from=2026-10-01&to=2026-10-31").header("Authorization", auth))
                .andExpect(status().isOk()); // exactly 31 days
        mockMvc.perform(get("/api/v1/drivers/me/earnings?from=2026-10-05&to=2026-10-01").header("Authorization", auth))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/drivers/me/earnings?from=not-a-date").header("Authorization", auth))
                .andExpect(status().isBadRequest());
    }
}
