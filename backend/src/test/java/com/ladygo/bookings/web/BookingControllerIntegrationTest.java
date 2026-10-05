package com.ladygo.bookings.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.customers.domain.Customer;
import com.ladygo.locations.domain.City;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.UserRole;
import com.ladygo.vehicles.domain.VehicleType;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;

/** docs/api/booking.md: the HTTP layer - status codes, auth, and the FARE_CHANGED response shape. */
@AutoConfigureMockMvc
class BookingControllerIntegrationTest extends PersistenceTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    private City srinagar() {
        return newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
    }

    private String tokenFor(Customer customer) {
        return jwtService.issueAccessToken(customer.getUserId(), UserRole.CUSTOMER, Duration.ofMinutes(15));
    }

    /** See BookingServiceIntegrationTest#commitFixtures - needed whenever a request reaches the
     *  REQUIRES_NEW booking insert, which runs on a connection that can't see this test's own
     *  otherwise-uncommitted fixture rows. */
    private void commitFixtures() {
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
    }

    @Test
    void estimateIsReachableWithoutAnyFixtureAndRejectsOutOfArea() throws Exception {
        Customer customer = newCustomer();
        mockMvc.perform(post("/api/v1/bookings/estimate")
                        .header("Authorization", "Bearer " + tokenFor(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupLatitude\":10,\"pickupLongitude\":10,"
                                + "\"destinationLatitude\":10.1,\"destinationLongitude\":10.1,\"vehicleType\":\"CAB\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("OUT_OF_SERVICE_AREA"));
    }

    @Test
    void createRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createReturns201ThenReplayReturns200() throws Exception {
        City city = srinagar();
        newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        commitFixtures();
        String idempotencyKey = UUID.randomUUID().toString();

        String estimateResponse = mockMvc.perform(post("/api/v1/bookings/estimate")
                        .header("Authorization", "Bearer " + tokenFor(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupLatitude\":34.08,\"pickupLongitude\":74.80,"
                                + "\"destinationLatitude\":34.13,\"destinationLongitude\":74.87,\"vehicleType\":\"CAB\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long freshFare = com.jayway.jsonpath.JsonPath.<Number>read(estimateResponse, "$.data.estimatedFare").longValue();

        String body = "{\"pickupLatitude\":34.08,\"pickupLongitude\":74.80,\"pickupAddress\":\"Lal Chowk\","
                + "\"destinationLatitude\":34.13,\"destinationLongitude\":74.87,\"destinationAddress\":\"Hazratbal\","
                + "\"vehicleType\":\"CAB\",\"paymentMethod\":\"CASH\",\"expectedFare\":" + freshFare + "}";

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + tokenFor(customer))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("SEARCHING_DRIVER"))
                .andExpect(jsonPath("$.data.ridePin").isNotEmpty());

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + tokenFor(customer))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void fareChangedCarriesTheFreshQuoteInTheBody() throws Exception {
        City city = srinagar();
        newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        String body = "{\"pickupLatitude\":34.08,\"pickupLongitude\":74.80,\"pickupAddress\":\"Lal Chowk\","
                + "\"destinationLatitude\":34.13,\"destinationLongitude\":74.87,\"destinationAddress\":\"Hazratbal\","
                + "\"vehicleType\":\"CAB\",\"paymentMethod\":\"CASH\",\"expectedFare\":1}";

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + tokenFor(customer))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FARE_CHANGED"))
                .andExpect(jsonPath("$.data.estimatedFare").isNumber());
    }

    @Test
    void getRejectsAnotherCustomersBookingWith404() throws Exception {
        Customer stranger = newCustomer();
        mockMvc.perform(get("/api/v1/bookings/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenFor(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void rideHistoryIsServedFromTheCustomerController() throws Exception {
        Customer customer = newCustomer();
        City city = srinagar();
        newBooking(customer, city, com.ladygo.bookings.domain.BookingStatus.SEARCHING_DRIVER, null);

        mockMvc.perform(get("/api/v1/customers/me/rides")
                        .header("Authorization", "Bearer " + tokenFor(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }
}
