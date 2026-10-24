package com.ladygo.admin.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import com.ladygo.rides.domain.RideFlag;
import com.ladygo.rides.domain.RideFlagType;
import com.ladygo.rides.repository.RideFlagRepository;
import com.ladygo.common.domain.ActorType;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Role gating is the actual access control here - an admin may read any ride/flag by design (same
 * non-IDOR shape as driver-document review), so these tests check that DRIVER/CUSTOMER tokens cannot
 * reach the admin surface at all, not per-object ownership.
 */
@AutoConfigureMockMvc
class AdminRideControllerIntegrationTest extends PersistenceTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private RideFlagRepository rideFlags;

    private String tokenFor(UUID userId, UserRole role) {
        return jwtService.issueAccessToken(userId, role, Duration.ofMinutes(15));
    }

    private RideFlag newFlag(Booking booking, Driver driver) {
        RideFlag flag = new RideFlag();
        flag.setBookingId(booking.getId());
        flag.setCustomerId(booking.getCustomerId());
        flag.setDriverId(driver.getId());
        flag.setType(RideFlagType.RIDER_SAFETY_CANCEL);
        flag.setRaisedBy(ActorType.CUSTOMER);
        return rideFlags.saveAndFlush(flag);
    }

    @Test
    void anAdminCanListRides() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId());
        UUID adminUserId = newUser(UserRole.ADMIN).getId();

        mockMvc.perform(get("/api/v1/admin/rides")
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    void aDriverCannotListRides() throws Exception {
        City city = newCity();
        Driver driver = newDriver(city);

        mockMvc.perform(get("/api/v1/admin/rides")
                        .header("Authorization", "Bearer " + tokenFor(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCustomerCannotListRides() throws Exception {
        Customer customer = newCustomer();

        mockMvc.perform(get("/api/v1/admin/rides")
                        .header("Authorization", "Bearer " + tokenFor(customer.getUserId(), UserRole.CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminCanGetRideDetailIncludingFlags() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId());
        newFlag(booking, driver);
        UUID adminUserId = newUser(UserRole.ADMIN).getId();

        mockMvc.perform(get("/api/v1/admin/rides/" + booking.getId())
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingId").value(booking.getId().toString()))
                .andExpect(jsonPath("$.data.flags[0].type").value("RIDER_SAFETY_CANCEL"));
    }

    /** docs/product/admin-web-design.md section 4 update: masked phones, never a full number, and
     *  pickup gated through BookingVisibilityPolicy rather than dumped unconditionally - currently
     *  that policy grants ADMIN the exact address in every status (BookingVisibilityPolicy.ADMIN row),
     *  so this asserts it IS present; if that policy is ever tightened, this test turns into a signal
     *  that the admin screen's own behaviour just changed too, which is the point of going through the
     *  policy instead of a separately-maintained "admins see everything" rule here. */
    @Test
    void rideDetailIncludesMaskedContactsCashStateAndPickupPerPolicy() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId());
        UUID adminUserId = newUser(UserRole.ADMIN).getId();

        String customerPhone = users.findById(customer.getUserId()).orElseThrow().getPhone();
        String driverPhone = users.findById(driver.getUserId()).orElseThrow().getPhone();

        mockMvc.perform(get("/api/v1/admin/rides/" + booking.getId())
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer.phoneMasked").value(org.hamcrest.Matchers.endsWith(
                        customerPhone.substring(customerPhone.length() - 4))))
                .andExpect(jsonPath("$.data.customer.phoneMasked").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(customerPhone.substring(0, customerPhone.length() - 4)))))
                .andExpect(jsonPath("$.data.driver.phoneMasked").value(org.hamcrest.Matchers.endsWith(
                        driverPhone.substring(driverPhone.length() - 4))))
                .andExpect(jsonPath("$.data.pickupAddress").exists())
                .andExpect(jsonPath("$.data.timeline").isArray());
    }

    @Test
    void listRidesCanBeFilteredByOpenFlagState() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driverWithFlag = newDriver(city);
        Driver driverWithoutFlag = newDriver(city);
        Booking flaggedBooking = newBooking(customer, city, BookingStatus.RIDE_STARTED, driverWithFlag.getId());
        newFlag(flaggedBooking, driverWithFlag);
        newBooking(customer, city, BookingStatus.RIDE_STARTED, driverWithoutFlag.getId());
        UUID adminUserId = newUser(UserRole.ADMIN).getId();

        mockMvc.perform(get("/api/v1/admin/rides").param("hasOpenFlags", "true")
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].bookingId").value(flaggedBooking.getId().toString()));
    }

    @Test
    void getRideDetailForAnUnknownBookingIs404() throws Exception {
        UUID adminUserId = newUser(UserRole.ADMIN).getId();

        mockMvc.perform(get("/api/v1/admin/rides/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAdminCanListAndReviewAFlag() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId());
        RideFlag flag = newFlag(booking, driver);
        UUID adminUserId = newUser(UserRole.ADMIN).getId();

        mockMvc.perform(get("/api/v1/admin/ride-flags")
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(flag.getId().toString()));

        mockMvc.perform(post("/api/v1/admin/ride-flags/" + flag.getId() + "/review")
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"looks fine, no action needed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.adminReviewedBy").value(adminUserId.toString()))
                .andExpect(jsonPath("$.data.adminReviewNote").value("looks fine, no action needed"));

        RideFlag reloaded = rideFlags.findById(flag.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getAdminReviewedAt()).isNotNull();
    }

    /** A flag already reviewed (by this admin or another) cannot be silently re-claimed - matches the
     *  idempotency-guard pattern used everywhere else in this codebase for a one-time state transition. */
    @Test
    void reviewingAnAlreadyReviewedFlagIsRejected() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId());
        RideFlag flag = newFlag(booking, driver);
        UUID adminUserId = newUser(UserRole.ADMIN).getId();

        mockMvc.perform(post("/api/v1/admin/ride-flags/" + flag.getId() + "/review")
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"first review\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/ride-flags/" + flag.getId() + "/review")
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"second review attempt\"}"))
                .andExpect(status().isBadRequest());
    }

    /** The note is required, not just the endpoint itself - an empty/blank note must be rejected the
     *  same way a missing one is, matching the document-rejection note's own validation. */
    @Test
    void reviewingAFlagWithoutANoteIsRejected() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId());
        RideFlag flag = newFlag(booking, driver);
        UUID adminUserId = newUser(UserRole.ADMIN).getId();

        mockMvc.perform(post("/api/v1/admin/ride-flags/" + flag.getId() + "/review")
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aDriverCannotReviewAFlag() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId());
        RideFlag flag = newFlag(booking, driver);

        mockMvc.perform(post("/api/v1/admin/ride-flags/" + flag.getId() + "/review")
                        .header("Authorization", "Bearer " + tokenFor(driver.getUserId(), UserRole.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"n/a\"}"))
                .andExpect(status().isForbidden());
    }
}
