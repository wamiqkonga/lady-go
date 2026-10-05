package com.ladygo.bookings.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * docs/api/driver-onboarding.md sections 4/5 - the HTTP layer: role gates actually take effect (not
 * just the service-level checks), ownership, and the visibility window for the rider selfie.
 */
@AutoConfigureMockMvc
class DriverBookingControllerIntegrationTest extends PersistenceTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    private String tokenFor(java.util.UUID userId, UserRole role) {
        return jwtService.issueAccessToken(userId, role, Duration.ofMinutes(15));
    }

    @Test
    void customerCannotCallTheDriverOnlyCancelEligibilityConcernEndpoint() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        mockMvc.perform(post("/api/v1/drivers/me/bookings/" + booking.getId() + "/cancel-eligibility-concern")
                        .header("Authorization", "Bearer " + tokenFor(customer.getUserId(), UserRole.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"concern\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theAssignedDriverCanCancelForAnEligibilityConcern() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        mockMvc.perform(post("/api/v1/drivers/me/bookings/" + booking.getId() + "/cancel-eligibility-concern")
                        .header("Authorization", "Bearer " + tokenFor(driver.getUserId(), UserRole.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"felt unsafe\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void anUnrelatedDriverCannotCancelSomeoneElsesBooking() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Driver stranger = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        mockMvc.perform(post("/api/v1/drivers/me/bookings/" + booking.getId() + "/cancel-eligibility-concern")
                        .header("Authorization", "Bearer " + tokenFor(stranger.getUserId(), UserRole.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"not mine\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void eitherPartyOnABookingCanFileAnEligibilityReport() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        mockMvc.perform(post("/api/v1/bookings/" + booking.getId() + "/eligibility-report")
                        .header("Authorization", "Bearer " + tokenFor(customer.getUserId(), UserRole.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"concern\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/bookings/" + booking.getId() + "/eligibility-report")
                        .header("Authorization", "Bearer " + tokenFor(driver.getUserId(), UserRole.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"concern\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void riderSelfieEndpointIsNotVisibleBeforeAcceptance() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        mockMvc.perform(get("/api/v1/drivers/me/bookings/" + booking.getId() + "/rider-selfie")
                        .header("Authorization", "Bearer " + tokenFor(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isNotFound());
    }
}
