package com.ladygo.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.service.BookingService;
import com.ladygo.bookings.web.dto.BookingRequest;
import com.ladygo.bookings.web.dto.FareEstimateRequest;
import com.ladygo.bookings.web.dto.FareEstimateResponse;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.locations.domain.City;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.pricing.domain.PricingRule;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.domain.RideStatus;
import com.ladygo.rides.service.RideLifecycleService;
import com.ladygo.users.domain.UserRole;
import com.ladygo.vehicles.domain.VehicleType;
import com.ladygo.verification.domain.SubjectType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pricing-change rules that depend on real commits: concurrency, the exclusion constraint as seen by other
 * connections, and the key money property - a fare change never alters a booking already made. Not wrapped in a test
 * transaction (every fixture is committed and removed in {@link #purge()}).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminPricingRuleCommittedIntegrationTest extends PricingRuleHttpSupport {

    @Autowired private BookingService bookingService;
    @Autowired private RideLifecycleService rideLifecycleService;

    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> customerIds = new ArrayList<>();
    private UUID cityId;

    @AfterEach
    void purge() {
        if (cityId == null) {
            return;
        }
        for (UUID customerId : customerIds) {
            jdbc.update("DELETE FROM ride_selfies WHERE customer_id = ?", customerId);
        }
        jdbc.update("DELETE FROM booking_events WHERE booking_id IN (SELECT id FROM bookings WHERE city_id = ?)", cityId);
        jdbc.update("DELETE FROM ride_flags WHERE ride_id IN (SELECT r.id FROM rides r JOIN bookings b ON b.id = r.booking_id "
                + "WHERE b.city_id = ?)", cityId);
        jdbc.update("DELETE FROM rides WHERE booking_id IN (SELECT id FROM bookings WHERE city_id = ?)", cityId);
        jdbc.update("DELETE FROM bookings WHERE city_id = ?", cityId);
        jdbc.update("DELETE FROM pricing_rules WHERE city_id = ?", cityId);
        for (UUID customerId : customerIds) {
            jdbc.update("DELETE FROM self_declarations WHERE subject_type = 'CUSTOMER' AND subject_id = ?", customerId);
        }
        jdbc.update("DELETE FROM drivers WHERE city_id = ?", cityId);
        for (UUID customerId : customerIds) {
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        }
        for (UUID userId : userIds) {
            jdbc.update("DELETE FROM audit_logs WHERE actor_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        jdbc.update("DELETE FROM cities WHERE id = ?", cityId);
    }

    private City srinagar() {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        cityId = city.getId();
        return city;
    }

    private String admin() {
        UUID id = newUser(UserRole.SUPER_ADMIN).getId();
        userIds.add(id);
        return steppedUpSuperAdminToken(id);
    }

    private Customer customer() {
        Customer customer = newCustomer();
        customerIds.add(customer.getId());
        userIds.add(customer.getUserId());
        acceptCurrentDeclaration(SubjectType.CUSTOMER, customer.getId());
        return customer;
    }

    private PricingRule seedRule(City city, long baseFare, long cancellationFee) {
        PricingRule rule = newPricingRule(city, VehicleType.BIKE);
        rule.setBaseFare(baseFare);
        rule.setCancellationFee(cancellationFee);
        return pricingRules.saveAndFlush(rule);
    }

    private Booking book(Customer customer) {
        FareEstimateResponse estimate = bookingService.estimate(customer.getUserId(),
                new FareEstimateRequest(34.08, 74.80, 34.13, 74.87, VehicleType.BIKE));
        UUID selfie = newRideSelfie(customer.getId());
        UUID bookingId = bookingService.create(customer.getUserId(), new BookingRequest(
                34.08, 74.80, "Lal Chowk", 34.13, 74.87, "Hazratbal", VehicleType.BIKE, PaymentMode.CASH, null,
                estimate.estimatedFare(), selfie), UUID.randomUUID().toString()).booking().id();
        return bookings.findById(bookingId).orElseThrow();
    }

    private UUID createdId(com.fasterxml.jackson.databind.JsonNode node) {
        return UUID.fromString(node.path("data").path("id").asText());
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    // ---- concurrency -----------------------------------------------------------------------------------------------

    @Test
    void twoAdminsSavingTheSameChangeTogetherGiveExactlyOneWinnerAndNoGapOrOverlap() throws Exception {
        City city = srinagar();
        PricingRule current = seedRule(city, 5000, 5000);
        String tokenA = admin();
        String tokenB = admin();
        Map<String, Object> a = createBody(city.getId(), "BIKE", current.getId(), "admin A");
        Map<String, Object> b = createBody(city.getId(), "BIKE", current.getId(), "admin B");
        b.put("baseFare", 5600);

        List<Integer> statuses = race(
                () -> create(tokenA, a).andReturn().getResponse().getStatus(),
                () -> create(tokenB, b).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertChainIsContiguous(city, 2);
    }

    @Test
    void twoAdminsSettingTheFirstFareTogetherGiveExactlyOneWinner() throws Exception {
        City city = srinagar();
        String tokenA = admin();
        String tokenB = admin();

        List<Integer> statuses = race(
                () -> create(tokenA, createBody(city.getId(), "BIKE", null, "A")).andReturn().getResponse().getStatus(),
                () -> create(tokenB, createBody(city.getId(), "BIKE", null, "B")).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pricing_rules WHERE city_id = ?", Integer.class, city.getId())).isEqualTo(1);
    }

    @Test
    void twoAdminsSchedulingAtTheSameTimeGiveExactlyOneWinner() throws Exception {
        City city = srinagar();
        PricingRule current = seedRule(city, 5000, 5000);
        String tokenA = admin();
        String tokenB = admin();
        String when = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        Map<String, Object> a = createBody(city.getId(), "BIKE", current.getId(), "A");
        Map<String, Object> b = createBody(city.getId(), "BIKE", current.getId(), "B");
        a.put("effectiveFrom", when);
        b.put("effectiveFrom", when);

        List<Integer> statuses = race(() -> create(tokenA, a).andReturn().getResponse().getStatus(),
                () -> create(tokenB, b).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertChainIsContiguous(city, 2);
    }

    // ---- a change never alters a booking already made --------------------------------------------------------------

    @Test
    void aBookingMadeBeforeAChangeKeepsItsOldRuleAndEstimatedFareAndALaterBookingUsesTheNewRule() throws Exception {
        City city = srinagar();
        PricingRule oldRule = seedRule(city, 5000, 5000);
        Booking before = book(customer());
        long oldEstimate = before.getEstimatedFare();
        assertThat(before.getPricingRuleId()).isEqualTo(oldRule.getId());

        Map<String, Object> change = createBody(city.getId(), "BIKE", oldRule.getId(), "fare rise");
        change.put("baseFare", 7000);
        UUID newRuleId = createdId(body(create(admin(), change).andExpect(status().isCreated())));

        Booking untouched = bookings.findById(before.getId()).orElseThrow();
        assertThat(untouched.getPricingRuleId()).isEqualTo(oldRule.getId());
        assertThat(untouched.getEstimatedFare()).isEqualTo(oldEstimate);
        Booking after = book(customer());
        assertThat(after.getPricingRuleId()).isEqualTo(newRuleId);
        assertThat(after.getEstimatedFare()).isGreaterThan(oldEstimate);
    }

    @Test
    void aRideEndedEarlyAfterAChangeIsStillPricedWithTheRuleOfItsBooking() throws Exception {
        City city = srinagar();
        PricingRule oldRule = seedRule(city, 5000, 4321);
        Customer customer = customer();
        Booking booking = book(customer);
        startedRideFor(booking, city);

        Map<String, Object> change = createBody(city.getId(), "BIKE", oldRule.getId(), "fee rise");
        change.put("cancellationFee", 6000);
        create(admin(), change).andExpect(status().isCreated());

        // no distance travelled -> the early end charges the cancellation fee of the booking's own rule (4321),
        // not that of the rule now in force (6000)
        rideLifecycleService.endRideEarlyAsCustomer(customer.getUserId(), booking.getId(), "feeling unwell", false);

        assertThat(bookings.findById(booking.getId()).orElseThrow().getFinalFare()).isEqualTo(4321L);
    }

    private Driver startedRideFor(Booking booking, City city) {
        Driver driver = newDriver(city);
        userIds.add(driver.getUserId());
        driver.setVerificationStatus(DriverVerificationStatus.APPROVED);
        driver.setAvailabilityStatus(DriverAvailability.BUSY);
        driver.setCurrentLocation(point(74.80, 34.08));
        driver.setCurrentLocationAt(Instant.now());
        driver.setCurrentLocationAccuracyM(5.0);
        drivers.saveAndFlush(driver);
        booking.setDriverId(driver.getId());
        booking.setStatus(BookingStatus.RIDE_STARTED);
        bookings.saveAndFlush(booking);
        Ride ride = newRide(booking, driver);
        ride.setStatus(RideStatus.STARTED);
        ride.setStartedAt(Instant.now().minusSeconds(60));
        ride.setStartLocation(point(74.80, 34.08));
        rides.saveAndFlush(ride);
        return driver;
    }

    // ---- scheduled change takes effect at its time -----------------------------------------------------------------

    @Test
    void aScheduledChangeTakesEffectExactlyAtItsTimeForNewEstimatesAndBookings() throws Exception {
        City city = srinagar();
        PricingRule oldRule = seedRule(city, 5000, 5000);
        Customer rider = customer();
        Instant when = Instant.now().plusMillis(2500).truncatedTo(ChronoUnit.MICROS);
        Map<String, Object> change = createBody(city.getId(), "BIKE", oldRule.getId(), "scheduled");
        change.put("baseFare", 7000);
        change.put("effectiveFrom", when.toString());
        UUID newRuleId = createdId(body(create(admin(), change).andExpect(status().isCreated())));

        long beforeFare = bookingService.estimate(rider.getUserId(),
                new FareEstimateRequest(34.08, 74.80, 34.13, 74.87, VehicleType.BIKE)).estimatedFare();
        assertThat(pricingRules.findActive(city.getId(), VehicleType.BIKE, when.minusNanos(1000))).map(PricingRule::getId)
                .contains(oldRule.getId());
        assertThat(pricingRules.findActive(city.getId(), VehicleType.BIKE, when)).map(PricingRule::getId).contains(newRuleId);

        Thread.sleep(Math.max(0, when.toEpochMilli() - Instant.now().toEpochMilli()) + 300);

        FareEstimateResponse afterFare = bookingService.estimate(rider.getUserId(),
                new FareEstimateRequest(34.08, 74.80, 34.13, 74.87, VehicleType.BIKE));
        assertThat(afterFare.estimatedFare()).isGreaterThan(beforeFare);
        assertThat(book(customer()).getPricingRuleId()).isEqualTo(newRuleId);
    }

    @Test
    void aChainOfChangesLeavesOneContiguousTimelineWithNoGapOrOverlap() throws Exception {
        City city = srinagar();
        String token = admin();
        UUID latest = createdId(body(create(token, createBody(city.getId(), "BIKE", null, "first")).andExpect(status().isCreated())));
        for (int i = 0; i < 4; i++) {
            Map<String, Object> next = createBody(city.getId(), "BIKE", latest, "change " + i);
            next.put("baseFare", 5500 + i * 100);
            latest = createdId(body(create(token, next).andExpect(status().isCreated())));
        }
        assertChainIsContiguous(city, 5);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private void assertChainIsContiguous(City city, int expectedRows) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT effective_from, effective_to FROM pricing_rules WHERE city_id = ? ORDER BY effective_from", city.getId());
        assertThat(rows).hasSize(expectedRows);
        for (int i = 0; i < rows.size() - 1; i++) {
            assertThat(rows.get(i).get("effective_to")).as("row %d closes where row %d opens", i, i + 1)
                    .isEqualTo(rows.get(i + 1).get("effective_from"));
        }
        assertThat(rows.get(rows.size() - 1).get("effective_to")).as("last row is open-ended").isNull();
    }

    @SafeVarargs
    private List<Integer> race(Callable<Integer>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch ready = new CountDownLatch(calls.length);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
