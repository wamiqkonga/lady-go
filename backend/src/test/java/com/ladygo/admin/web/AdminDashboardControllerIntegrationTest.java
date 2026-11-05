package com.ladygo.admin.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** GET /api/v1/admin/dashboard: SUPPORT and above, read only, counts and timestamps only. */
@AutoConfigureMockMvc
class AdminDashboardControllerIntegrationTest extends PersistenceTestSupport {

    private static final String URL = "/api/v1/admin/dashboard";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    private ResultActions getAs(UserRole role) throws Exception {
        User user = newUser(role);
        return mockMvc.perform(get(URL).header("Authorization",
                "Bearer " + jwtService.issueAccessToken(user.getId(), role, Duration.ofMinutes(15))));
    }

    @Test
    void supportAdminAndSuperAdminCanRead() throws Exception {
        for (UserRole role : new UserRole[] {UserRole.SUPPORT, UserRole.ADMIN, UserRole.SUPER_ADMIN}) {
            getAs(role).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.timeZone").value("Asia/Kolkata"))
                    .andExpect(jsonPath("$.data.drivers.active").isNumber())
                    .andExpect(jsonPath("$.data.rides.cashFaresTodayRupees").isNumber())
                    .andExpect(jsonPath("$.data.customers.total").isNumber())
                    .andExpect(jsonPath("$.data.verification.targetHours").value(24))
                    .andExpect(jsonPath("$.data.safety.sosTargetMinutes").value(5))
                    .andExpect(jsonPath("$.data.adoption.weeklyRides", hasSize(8)))
                    .andExpect(jsonPath("$.data.loginAlerts.windowMinutes").isNumber());
        }
    }

    @Test
    void customerAndDriverTokensAreForbiddenAndAnonymousIsUnauthorized() throws Exception {
        getAs(UserRole.CUSTOMER).andExpect(status().isForbidden());
        getAs(UserRole.DRIVER).andExpect(status().isForbidden());
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void isReadOnly() throws Exception {
        User admin = newUser(UserRole.SUPER_ADMIN);
        String token = "Bearer " + jwtService.issueAccessToken(admin.getId(), UserRole.SUPER_ADMIN, Duration.ofMinutes(15));
        mockMvc.perform(post(URL).header("Authorization", token)).andExpect(status().is4xxClientError());
    }

    @Test
    void responseCarriesNoPersonalData() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newOnlineDriver(city, 74.80, 34.08);
        jdbc.update("UPDATE users SET name = 'Zainab Mirzada', email = 'zainab.mirzada@example.test' WHERE id = ?",
                customer.getUserId());
        jdbc.update("UPDATE users SET name = 'Aisha Khanzada' WHERE id = ?", driver.getUserId());
        Booking booking = newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId());
        jdbc.update("UPDATE bookings SET pickup_address = 'Secret Lane 9' WHERE id = ?", booking.getId());
        String customerPhone = users.findById(customer.getUserId()).orElseThrow().getPhone();
        String driverPhone = users.findById(driver.getUserId()).orElseThrow().getPhone();

        getAs(UserRole.SUPPORT).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Zainab"))))
                .andExpect(content().string(not(containsString("Mirzada"))))
                .andExpect(content().string(not(containsString("Aisha"))))
                .andExpect(content().string(not(containsString("Khanzada"))))
                .andExpect(content().string(not(containsString("example.test"))))
                .andExpect(content().string(not(containsString("Secret Lane"))))
                .andExpect(content().string(not(containsString(customerPhone))))
                .andExpect(content().string(not(containsString(driverPhone))))
                .andExpect(content().string(not(containsString("latitude"))))
                .andExpect(content().string(not(containsString("\"email\""))));
    }

    @Test
    void jsonKeysAreAnAllowListOfCountsAndTimestamps() throws Exception {
        getAs(UserRole.ADMIN)
                .andExpect(jsonPath("$.data.drivers.keys()").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "active", "online", "onlineMaxAgeMinutes")))
                .andExpect(jsonPath("$.data.customers.keys()").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "total", "newLast7Days")));
    }
}
