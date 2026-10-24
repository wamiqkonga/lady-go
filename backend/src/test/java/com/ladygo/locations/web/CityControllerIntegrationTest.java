package com.ladygo.locations.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.locations.domain.City;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Closes the gap found while building the driver registration website: nothing exposed which
 *  cities exist, even though POST /drivers/me requires a cityId. */
@AutoConfigureMockMvc
class CityControllerIntegrationTest extends PersistenceTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    private String tokenFor(UUID userId, UserRole role) {
        return jwtService.issueAccessToken(userId, role, Duration.ofMinutes(15));
    }

    @Test
    void aDriverCanListActiveCities() throws Exception {
        City city = newCity();
        UUID driverUserId = newUser(UserRole.DRIVER).getId();

        mockMvc.perform(get("/api/v1/cities")
                        .header("Authorization", "Bearer " + tokenFor(driverUserId, UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + city.getId() + "')]").exists());
    }

    @Test
    void aCustomerCanListActiveCitiesToo() throws Exception {
        UUID customerUserId = newUser(UserRole.CUSTOMER).getId();

        mockMvc.perform(get("/api/v1/cities")
                        .header("Authorization", "Bearer " + tokenFor(customerUserId, UserRole.CUSTOMER)))
                .andExpect(status().isOk());
    }

    @Test
    void anUnauthenticatedCallerCannotListCities() throws Exception {
        mockMvc.perform(get("/api/v1/cities")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCannotListCitiesThroughThisEndpoint() throws Exception {
        UUID adminUserId = newUser(UserRole.ADMIN).getId();

        mockMvc.perform(get("/api/v1/cities")
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN)))
                .andExpect(status().isForbidden());
    }
}
