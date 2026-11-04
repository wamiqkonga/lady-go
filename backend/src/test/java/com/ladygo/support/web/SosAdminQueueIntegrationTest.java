package com.ladygo.support.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** An SOS raised through the rider and driver endpoints is the same row the admin Safety screen reads: CRITICAL, OPEN,
 *  with its position and a CREATED timeline event, and it can be acknowledged and resolved on the one canonical API. */
@AutoConfigureMockMvc
class SosAdminQueueIntegrationTest extends PersistenceTestSupport {

    private static final String QUEUE = "/api/v1/admin/safety/incidents";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user.getId(), user.getRole(), Duration.ofMinutes(10));
    }

    private ResultActions raise(String url, User caller, UUID bookingId) throws Exception {
        String booking = bookingId == null ? "" : ",\"bookingId\":\"" + bookingId + "\"";
        return mockMvc.perform(post(url).header("Authorization", bearer(caller))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":34.0837,\"longitude\":74.7973,\"notes\":\"help\"" + booking + "}"));
    }

    private String incidentId(ResultActions raised) throws Exception {
        raised.andExpect(status().isOk());
        String body = raised.andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.data.incidentId");
    }

    private long openCount(User staff, String field) throws Exception {
        String body = mockMvc.perform(get(QUEUE + "/open-count").header("Authorization", bearer(staff)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.data." + field)).longValue();
    }

    @Test
    void aRiderSosWithNoBookingShowsInTheQueueAsCriticalOpenWithItsPositionAndCanBeActedOn() throws Exception {
        User staff = newUser(UserRole.SUPPORT);
        Customer customer = newCustomer();
        User rider = users.findById(customer.getUserId()).orElseThrow();
        long before = openCount(staff, "unresolved");
        long criticalBefore = openCount(staff, "critical");

        String id = incidentId(raise("/api/v1/customers/me/sos", rider, null));

        assertThat(openCount(staff, "unresolved")).isEqualTo(before + 1);
        assertThat(openCount(staff, "critical")).isEqualTo(criticalBefore + 1);
        mockMvc.perform(get(QUEUE).header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.id=='" + id + "')].type").value("SOS"))
                .andExpect(jsonPath("$.data.content[?(@.id=='" + id + "')].severity").value("CRITICAL"))
                .andExpect(jsonPath("$.data.content[?(@.id=='" + id + "')].status").value("OPEN"))
                .andExpect(jsonPath("$.data.content[?(@.id=='" + id + "')].hasLocation").value(true));
        // No booking and no ride: the detail must still open (it used to fail on the missing ride).
        mockMvc.perform(get(QUEUE + "/" + id).header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.severity").value("CRITICAL"))
                .andExpect(jsonPath("$.data.legalHold").value(true))
                .andExpect(jsonPath("$.data.location.lat").value(34.0837))
                .andExpect(jsonPath("$.data.location.lng").value(74.7973))
                .andExpect(jsonPath("$.data.timeline[0].eventType").value("CREATED"))
                .andExpect(jsonPath("$.data.reporter.role").value("CUSTOMER"));

        mockMvc.perform(post(QUEUE + "/" + id + "/acknowledge").header("Authorization", bearer(staff)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"));
        mockMvc.perform(post(QUEUE + "/" + id + "/resolve").header("Authorization", bearer(staff))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"RESOLVED_BY_PHONE\",\"note\":\"done\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RESOLVED"));
        assertThat(openCount(staff, "unresolved")).isEqualTo(before);
    }

    @Test
    void aDriverSosOnARideShowsItsRideAndBookingAndTheOldQueuePathIsGone() throws Exception {
        User staff = newUser(UserRole.ADMIN);
        City city = newCity();
        Driver driver = newDriver(city);
        Booking booking = newBooking(newCustomer(), city, BookingStatus.RIDE_STARTED, driver.getId());
        var ride = newRide(booking, driver);
        User driverUser = users.findById(driver.getUserId()).orElseThrow();

        String id = incidentId(raise("/api/v1/drivers/me/sos", driverUser, booking.getId()));

        mockMvc.perform(get(QUEUE + "/" + id).header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("SOS"))
                .andExpect(jsonPath("$.data.severity").value("CRITICAL"))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.rideId").value(ride.getId().toString()))
                .andExpect(jsonPath("$.data.bookingId").value(booking.getId().toString()))
                .andExpect(jsonPath("$.data.reporter.role").value("DRIVER"))
                .andExpect(jsonPath("$.data.timeline[0].eventType").value("CREATED"));
        mockMvc.perform(post(QUEUE + "/" + id + "/acknowledge").header("Authorization", bearer(staff)))
                .andExpect(status().isOk());
        mockMvc.perform(post(QUEUE + "/" + id + "/resolve").header("Authorization", bearer(staff))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"FALSE_ALARM\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RESOLVED"));

        // The duplicate read-only queue was removed: only one admin path can see or change incidents.
        mockMvc.perform(get("/api/v1/admin/safety-incidents").header("Authorization", bearer(staff)))
                .andExpect(status().isNotFound());
    }
}
