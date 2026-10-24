package com.ladygo.ratings.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import com.ladygo.ratings.domain.RaterRole;
import com.ladygo.ratings.domain.Rating;
import com.ladygo.ratings.repository.RatingRepository;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.domain.RideFlagType;
import com.ladygo.rides.domain.RideStatus;
import com.ladygo.rides.repository.RideFlagRepository;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.RideScenarioSupport;
import com.ladygo.users.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** docs/api/driver-earnings-ratings.md section 4.3 and 7. */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RatingControllerIntegrationTest extends RideScenarioSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private RatingRepository ratings;
    @Autowired private RideFlagRepository rideFlags;

    private City city;
    private Customer customer;
    private Driver driver;

    private void people() {
        city = newCity();
        customer = newCustomer();
        driver = newDriver(city);
    }

    private String token(UUID userId, UserRole role) {
        return "Bearer " + jwtService.issueAccessToken(userId, role, Duration.ofMinutes(15));
    }

    private Ride finishedRide() {
        return completedRide(city, customer, driver, 10_000, Instant.now().minus(Duration.ofHours(1)));
    }

    private ResultActions riderRates(Customer c, Ride ride, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings/" + ride.getBookingId() + "/rating")
                .header("Authorization", token(c.getUserId(), UserRole.CUSTOMER))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions driverRates(Driver d, UUID bookingId, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/drivers/me/bookings/" + bookingId + "/rating")
                .header("Authorization", token(d.getUserId(), UserRole.DRIVER))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private Driver reloaded() {
        flushAndClear();
        return drivers.findById(driver.getId()).orElseThrow();
    }

    @Test
    void aRiderRatingUpdatesTheDriversAverageAndCountInTheSameTransaction() throws Exception {
        people();
        Ride first = finishedRide();
        Ride second = finishedRide();

        riderRates(customer, first, "{\"score\":5}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(5))
                .andExpect(jsonPath("$.data.bookingId").value(first.getBookingId().toString()));
        riderRates(customer, second, "{\"score\":4,\"tags\":[\"POLITE\",\"ON_TIME\"]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags.length()").value(2));

        Driver d = reloaded();
        assertThat(d.getRatingCount()).isEqualTo(2);
        assertThat(d.getRatingAvg()).isEqualByComparingTo("4.50");
    }

    @Test
    void aDriverRatingARiderDoesNotTouchTheDriversOwnAggregate() throws Exception {
        people();
        Ride ride = finishedRide();

        driverRates(driver, ride.getBookingId(), "{\"score\":3,\"tags\":[\"RESPECTFUL\"],\"comment\":\"fine\"}")
                .andExpect(status().isOk());

        Rating stored = ratings.findByRideIdAndRaterId(ride.getId(), driver.getUserId()).orElseThrow();
        assertThat(stored.getRaterRole()).isEqualTo(RaterRole.DRIVER);
        assertThat(stored.getRateeId()).isEqualTo(customer.getUserId()); // derived server side
        Driver d = reloaded();
        assertThat(d.getRatingCount()).isZero();
    }

    @Test
    void anIdenticalResubmitIsIdempotentButADifferentOneConflicts() throws Exception {
        people();
        Ride ride = finishedRide();
        String body = "{\"score\":4,\"comment\":\"  nice  \",\"tags\":[\"POLITE\"]}";

        riderRates(customer, ride, body).andExpect(status().isOk());
        riderRates(customer, ride, "{\"score\":4,\"comment\":\"nice\",\"tags\":[\"POLITE\"]}").andExpect(status().isOk());
        riderRates(customer, ride, "{\"score\":5,\"comment\":\"nice\",\"tags\":[\"POLITE\"]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_RATED"));

        assertThat(ratings.findAll().stream().filter(r -> r.getRideId().equals(ride.getId()))).hasSize(1);
        assertThat(reloaded().getRatingCount()).isEqualTo(1);
    }

    @Test
    void onlyACompletedRideCanBeRated() throws Exception {
        people();
        Ride ride = finishedRide();
        ride.setStatus(RideStatus.STARTED);
        rides.saveAndFlush(ride);

        riderRates(customer, ride, "{\"score\":5}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BOOKING_INVALID_STATE"));
        driverRates(driver, ride.getBookingId(), "{\"score\":5}").andExpect(status().isConflict());
    }

    @Test
    void theRatingWindowIsSevenDaysAfterCompletion() throws Exception {
        people();
        Ride inside = completedRide(city, customer, driver, 1_000, Instant.now().minus(Duration.ofDays(6)));
        Ride outside = completedRide(city, customer, driver, 1_000, Instant.now().minus(Duration.ofDays(8)));

        riderRates(customer, inside, "{\"score\":5}").andExpect(status().isOk());
        riderRates(customer, outside, "{\"score\":5}").andExpect(status().isConflict());
        driverRates(driver, outside.getBookingId(), "{\"score\":5}").andExpect(status().isConflict());
    }

    @Test
    void onlyTheTwoParticipantsMayRateAndStrangersGetNotFound() throws Exception {
        people();
        Ride ride = finishedRide();
        Customer strangerRider = newCustomer();
        Driver strangerDriver = newDriver(city);

        riderRates(strangerRider, ride, "{\"score\":1}").andExpect(status().isNotFound());
        driverRates(strangerDriver, ride.getBookingId(), "{\"score\":1}").andExpect(status().isNotFound());
        // a booking id that does not exist looks identical
        driverRates(driver, UUID.randomUUID(), "{\"score\":1}").andExpect(status().isNotFound());
        // role gating: a driver token on the rider endpoint, and vice versa
        mockMvc.perform(post("/api/v1/bookings/" + ride.getBookingId() + "/rating")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"score\":5}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/drivers/me/bookings/" + ride.getBookingId() + "/rating")
                        .header("Authorization", token(customer.getUserId(), UserRole.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"score\":5}"))
                .andExpect(status().isForbidden());

        assertThat(ratings.findAll().stream().filter(r -> r.getRideId().equals(ride.getId()))).isEmpty();
    }

    @Test
    void badInputIsRejected() throws Exception {
        people();
        Ride ride = finishedRide();

        riderRates(customer, ride, "{\"score\":0}").andExpect(status().isBadRequest());
        riderRates(customer, ride, "{\"score\":6}").andExpect(status().isBadRequest());
        riderRates(customer, ride, "{}").andExpect(status().isBadRequest());
        riderRates(customer, ride, "{\"score\":5,\"comment\":\"" + "x".repeat(501) + "\"}")
                .andExpect(status().isBadRequest());
        riderRates(customer, ride, "{\"score\":5,\"tags\":[\"NOT_A_TAG\"]}").andExpect(status().isBadRequest());
        riderRates(customer, ride, "{\"score\":5,\"tags\":[\"RESPECTFUL\"]}").andExpect(status().isBadRequest()); // driver-side tag
        riderRates(customer, ride,
                "{\"score\":5,\"tags\":[\"POLITE\",\"SAFE_DRIVING\",\"CLEAN_VEHICLE\",\"ON_TIME\",\"POLITE\"]}")
                .andExpect(status().isOk()); // duplicates collapse, 4 distinct tags
        assertThat(ratings.findAll().stream().filter(r -> r.getRideId().equals(ride.getId()))).hasSize(1);
    }

    @Test
    void aFiveHundredCharacterCommentIsAccepted() throws Exception {
        people();
        riderRates(customer, finishedRide(), "{\"score\":5,\"comment\":\"" + "y".repeat(500) + "\"}")
                .andExpect(status().isOk());
    }

    @Test
    void lowScoresRaiseAnAdminFlagAndHighScoresDoNot() throws Exception {
        people();
        Ride low = finishedRide();
        Ride ok = finishedRide();
        Ride driverSideLow = finishedRide();

        riderRates(customer, low, "{\"score\":2}").andExpect(status().isOk());
        riderRates(customer, ok, "{\"score\":3}").andExpect(status().isOk());
        driverRates(driver, driverSideLow.getBookingId(), "{\"score\":1}").andExpect(status().isOk());

        assertThat(rideFlags.findByBookingIdAndType(low.getBookingId(), RideFlagType.LOW_RATING)).hasSize(1);
        assertThat(rideFlags.findByBookingIdAndType(ok.getBookingId(), RideFlagType.LOW_RATING)).isEmpty();
        assertThat(rideFlags.findByBookingIdAndType(driverSideLow.getBookingId(), RideFlagType.LOW_RATING)).hasSize(1);
        assertThat(rideFlags.findByBookingIdAndType(low.getBookingId(), RideFlagType.LOW_RATING).get(0).getDriverId())
                .isEqualTo(driver.getId());
    }

    @Test
    void aThirdOneStarFromTheSameRiderToTheSameDriverInAWeekRaisesARepeatFlag() throws Exception {
        people();
        Ride a = finishedRide();
        Ride b = finishedRide();
        Ride c = finishedRide();

        riderRates(customer, a, "{\"score\":1}").andExpect(status().isOk());
        riderRates(customer, b, "{\"score\":1}").andExpect(status().isOk());
        assertThat(rideFlags.findByBookingIdAndType(b.getBookingId(), RideFlagType.REPEATED_LOW_RATING)).isEmpty();
        riderRates(customer, c, "{\"score\":1}").andExpect(status().isOk());

        assertThat(rideFlags.findByBookingIdAndType(c.getBookingId(), RideFlagType.REPEATED_LOW_RATING)).hasSize(1);
    }

    @Test
    void furtherOneStarsDoNotRaiseAnotherRepeatFlagWhileOneIsUnreviewed() throws Exception {
        people();
        Ride[] rs = {finishedRide(), finishedRide(), finishedRide(), finishedRide(), finishedRide()};
        for (Ride r : rs) {
            riderRates(customer, r, "{\"score\":1}").andExpect(status().isOk());
        }
        long repeated = java.util.Arrays.stream(rs)
                .mapToLong(r -> rideFlags.findByBookingIdAndType(r.getBookingId(), RideFlagType.REPEATED_LOW_RATING).size())
                .sum();
        assertThat(repeated).isEqualTo(1);

        // once ops has reviewed it, a new one may be raised again
        rideFlags.findByBookingIdAndType(rs[2].getBookingId(), RideFlagType.REPEATED_LOW_RATING).forEach(f -> {
            f.setAdminReviewedAt(Instant.now());
            rideFlags.saveAndFlush(f);
        });
        Ride next = finishedRide();
        riderRates(customer, next, "{\"score\":1}").andExpect(status().isOk());
        assertThat(rideFlags.findByBookingIdAndType(next.getBookingId(), RideFlagType.REPEATED_LOW_RATING)).hasSize(1);
    }

    @Test
    void ratingSubmissionsAreRateLimitedPerUser() throws Exception {
        people();
        setConfig("ratings.submit.max_per_hour", "2");
        Ride a = finishedRide();
        Ride b = finishedRide();
        Ride c = finishedRide();

        riderRates(customer, a, "{\"score\":5}").andExpect(status().isOk());
        riderRates(customer, b, "{\"score\":5}").andExpect(status().isOk());
        riderRates(customer, c, "{\"score\":5}").andExpect(status().isTooManyRequests());
        // an idempotent resubmit does not burn the budget
        riderRates(customer, a, "{\"score\":5}").andExpect(status().isOk());
    }

    @Test
    void theDriverSummaryShowsAggregatesButNeverCommentsAndCommentsAreNeverLogged(CapturedOutput output) throws Exception {
        people();
        String secret = "the-driver-was-rude-unique-marker-7781";
        riderRates(customer, finishedRide(), "{\"score\":5,\"tags\":[\"POLITE\"]}").andExpect(status().isOk());
        riderRates(customer, finishedRide(), "{\"score\":2,\"comment\":\"" + secret + "\",\"tags\":[\"POLITE\",\"ON_TIME\"]}")
                .andExpect(status().isOk());
        riderRates(customer, finishedRide(), "{\"score\":5}").andExpect(status().isOk());
        riderRates(customer, finishedRide(), "{\"score\":5}").andExpect(status().isOk());
        riderRates(customer, finishedRide(), "{\"score\":3}").andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/v1/drivers/me/ratings/summary")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(5))
                .andExpect(jsonPath("$.data.avg").value(4.0))
                .andExpect(jsonPath("$.data.distribution.5").value(3))
                .andExpect(jsonPath("$.data.distribution.3").value(1))
                .andExpect(jsonPath("$.data.distribution.2").value(1))
                .andExpect(jsonPath("$.data.distribution.1").value(0))
                .andExpect(jsonPath("$.data.recentTagCounts.POLITE").value(2))
                .andExpect(jsonPath("$.data.recentTagCounts.ON_TIME").value(1))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(secret).doesNotContain("comment");
        assertThat(output.getAll()).doesNotContain(secret);
    }

    @Test
    void belowFiveRatingsTheSummaryHidesTheDistributionAndTagsButKeepsAvgAndCount() throws Exception {
        people();
        riderRates(customer, finishedRide(), "{\"score\":5,\"tags\":[\"POLITE\"]}").andExpect(status().isOk());
        riderRates(customer, finishedRide(), "{\"score\":2,\"tags\":[\"ON_TIME\"]}").andExpect(status().isOk());
        riderRates(customer, finishedRide(), "{\"score\":5}").andExpect(status().isOk());
        riderRates(customer, finishedRide(), "{\"score\":4}").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/drivers/me/ratings/summary")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(4))
                .andExpect(jsonPath("$.data.avg").value(4.0))
                .andExpect(jsonPath("$.data.distribution.1").value(0))
                .andExpect(jsonPath("$.data.distribution.2").value(0))
                .andExpect(jsonPath("$.data.distribution.3").value(0))
                .andExpect(jsonPath("$.data.distribution.4").value(0))
                .andExpect(jsonPath("$.data.distribution.5").value(0))
                .andExpect(jsonPath("$.data.recentTagCounts").isEmpty());
    }

    @Test
    void anUnknownStoredTagNameIsSkippedInsteadOfBreakingTheSummary() throws Exception {
        people();
        for (int i = 0; i < 5; i++) {
            riderRates(customer, finishedRide(), "{\"score\":5,\"tags\":[\"POLITE\"]}").andExpect(status().isOk());
        }
        flushAndClear();
        jdbc.update("UPDATE ratings SET tags = ARRAY['POLITE', 'REMOVED_LONG_AGO'] "
                + "WHERE id = (SELECT id FROM ratings WHERE ratee_id = ? LIMIT 1)", driver.getUserId());

        mockMvc.perform(get("/api/v1/drivers/me/ratings/summary")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recentTagCounts.POLITE").value(5))
                .andExpect(jsonPath("$.data.recentTagCounts.REMOVED_LONG_AGO").doesNotExist());
    }

    @Test
    void theSummaryIsDriverOnly() throws Exception {
        people();
        mockMvc.perform(get("/api/v1/drivers/me/ratings/summary")
                        .header("Authorization", token(customer.getUserId(), UserRole.CUSTOMER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/drivers/me/ratings/summary")).andExpect(status().isUnauthorized());
    }
}
