package com.ladygo.ratings.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import com.ladygo.ratings.web.dto.SubmitRatingRequest;
import com.ladygo.rides.domain.Ride;
import com.ladygo.testsupport.RideScenarioSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real concurrency needs committed data and one transaction per thread, so this test opts OUT of the
 * rolled-back test transaction and removes everything it created afterwards.
 */
class RatingConcurrencyIntegrationTest extends RideScenarioSupport {

    @Autowired private RatingService ratingService;

    private final List<Customer> customersCreated = new ArrayList<>();
    private Driver driver;
    private City city;

    @AfterEach
    void cleanUp() {
        if (driver == null) {
            return;
        }
        jdbc.update("DELETE FROM ride_flags WHERE driver_id = ?", driver.getId());
        jdbc.update("DELETE FROM ratings WHERE ride_id IN (SELECT id FROM rides WHERE driver_id = ?)", driver.getId());
        jdbc.update("DELETE FROM rides WHERE driver_id = ?", driver.getId());
        jdbc.update("DELETE FROM bookings WHERE driver_id = ?", driver.getId());
        jdbc.update("DELETE FROM drivers WHERE id = ?", driver.getId());
        jdbc.update("DELETE FROM users WHERE id = ?", driver.getUserId());
        for (Customer c : customersCreated) {
            jdbc.update("DELETE FROM customers WHERE id = ?", c.getId());
            jdbc.update("DELETE FROM users WHERE id = ?", c.getUserId());
        }
        jdbc.update("DELETE FROM auth_rate_limits WHERE bucket_key LIKE 'rating_submit:%'");
        jdbc.update("DELETE FROM cities WHERE id = ?", city.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void manySimultaneousRatingsLeaveTheAverageAndCountExact() throws Exception {
        city = newCity();
        driver = newDriver(city);
        int n = 12;
        List<Customer> raters = new ArrayList<>();
        List<Ride> rideList = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Customer c = newCustomer();
            customersCreated.add(c);
            raters.add(c);
            rideList.add(completedRide(city, c, driver, 1_000, Instant.now().minus(Duration.ofMinutes(5))));
        }
        int[] scores = {5, 4, 3, 5, 2, 1, 4, 4, 5, 3, 2, 5};
        long expectedSum = 0;
        for (int s : scores) {
            expectedSum += s;
        }

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                go.await();
                ratingService.rateDriver(raters.get(idx).getUserId(), rideList.get(idx).getBookingId(),
                        new SubmitRatingRequest(scores[idx], null, null));
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        Driver d = drivers.findById(driver.getId()).orElseThrow();
        assertThat(d.getRatingCount()).isEqualTo(n);
        assertThat(d.getRatingAvg()).isEqualByComparingTo(RatingService.average(expectedSum, n));
    }
}
