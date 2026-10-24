package com.ladygo.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.repository.RideEarningRepository;
import com.ladygo.drivers.domain.RideEarning;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.ratings.domain.RaterRole;
import com.ladygo.ratings.domain.Rating;
import com.ladygo.ratings.repository.RatingRepository;
import com.ladygo.rides.domain.Ride;
import com.ladygo.testsupport.RideScenarioSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** V31: ride_earnings and ratings.tags. */
class RideEarningsSchemaTest extends RideScenarioSupport {

    @Autowired private RideEarningRepository earnings;
    @Autowired private RatingRepository ratings;

    private Ride ride() {
        var city = newCity();
        return completedRide(city, newCustomer(), newDriver(city), 1_000, Instant.now());
    }

    private int insert(Ride ride, long gross, int bps, long commission, long net) {
        return earnings.insertIfAbsent(ride.getId(), ride.getDriverId(), gross, bps, commission, net, "INR", "CASH", Instant.now());
    }

    @Test
    void anEarningsRowRoundTripsAndTheRideIdIsUnique() {
        Ride ride = ride();
        assertThat(insert(ride, 1_000, 1500, 150, 850)).isEqualTo(1);
        assertThat(insert(ride, 1_000, 1500, 150, 850)).isZero(); // replay: ignored, no exception
        flushAndClear();

        RideEarning row = earnings.findByRideId(ride.getId()).orElseThrow();
        assertThat(row.getGross()).isEqualTo(1_000);
        assertThat(row.getNet()).isEqualTo(850);
        assertThat(row.getPaymentMethod()).isEqualTo(PaymentMode.CASH);
        assertThat(row.getPayoutBatchId()).isNull();
        assertThat(row.getSettledAt()).isNull();
        assertThat(earnings.findAll().stream().filter(e -> e.getRideId().equals(ride.getId()))).hasSize(1);
    }

    @Test
    void theMoneyConstraintsRejectInconsistentRows() {
        assertThatThrownBy(() -> insert(ride(), 1_000, 0, 0, 999)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void commissionCannotExceedGrossOrBeNegative() {
        Ride ride = ride();
        assertThatThrownBy(() -> insert(ride, 1_000, 0, 1_001, -1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ratingTagsRoundTripAndAreCappedAtFive() {
        Ride ride = ride();
        Rating ok = rating(ride, new String[] {"POLITE", "ON_TIME"});
        ratings.saveAndFlush(ok);
        flushAndClear();
        assertThat(ratings.findByRideIdAndRaterId(ride.getId(), ok.getRaterId()).orElseThrow().getTags())
                .containsExactly("POLITE", "ON_TIME");
    }

    @Test
    void moreThanFiveTagsAreRejectedByTheDatabase() {
        Ride ride = ride();
        Rating tooMany = rating(ride, new String[] {"A", "B", "C", "D", "E", "F"});
        assertThatThrownBy(() -> ratings.saveAndFlush(tooMany)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theCommissionKeyIsSeededEmptyMeaningUnset() {
        String value = jdbc.queryForObject(
                "SELECT value FROM configuration WHERE key = 'earnings.commission.bps' AND scope = 'GLOBAL'", String.class);
        assertThat(value).isEmpty();
    }

    private Rating rating(Ride ride, String[] tags) {
        Driver driver = drivers.findById(ride.getDriverId()).orElseThrow();
        Rating rating = new Rating();
        rating.setRideId(ride.getId());
        rating.setRaterId(customers.findById(ride.getCustomerId()).orElseThrow().getUserId());
        rating.setRateeId(driver.getUserId());
        rating.setRaterRole(RaterRole.CUSTOMER);
        rating.setScore((short) 5);
        rating.setTags(tags);
        return rating;
    }
}
