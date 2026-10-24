package com.ladygo.drivers.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** docs/api/driver-earnings-ratings.md section 6, B1. */
@AutoConfigureMockMvc
class DriverAvailabilityControllerIntegrationTest extends PersistenceTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;

    private String token(UUID userId, UserRole role) {
        return "Bearer " + jwtService.issueAccessToken(userId, role, Duration.ofMinutes(15));
    }

    @Test
    void aDriverReadsHerCurrentAvailabilityInTheEnvelope() throws Exception {
        Driver driver = newDriver(newCity());
        mockMvc.perform(get("/api/v1/drivers/me/availability")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OFFLINE"));

        driver.setAvailabilityStatus(DriverAvailability.SUSPENDED);
        drivers.saveAndFlush(driver);
        mockMvc.perform(get("/api/v1/drivers/me/availability")
                        .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
    }

    @Test
    void aCustomerCannotReadDriverAvailabilityAndAnonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/drivers/me/availability")
                        .header("Authorization", token(newCustomer().getUserId(), UserRole.CUSTOMER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/drivers/me/availability")).andExpect(status().isUnauthorized());
    }
}
