package com.ladygo.maps.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.customers.domain.Customer;
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

/** docs/product/map-design.md section 5.1: the HTTP layer of POST /api/v1/maps/reverse-geocode. */
@AutoConfigureMockMvc
class MapsControllerIntegrationTest extends PersistenceTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    private String tokenFor(Customer customer) {
        return jwtService.issueAccessToken(customer.getUserId(), UserRole.CUSTOMER, Duration.ofMinutes(15));
    }

    private static String body(double latitude, double longitude) {
        return "{\"latitude\":" + latitude + ",\"longitude\":" + longitude + "}";
    }

    @Test
    void pinInsideAServedCityReturnsTheAddressAndTheCityName() throws Exception {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        mockMvc.perform(post("/api/v1/maps/reverse-geocode")
                        .header("Authorization", "Bearer " + tokenFor(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(34.08, 74.80)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.address").value(containsString("Mock address near")))
                .andExpect(jsonPath("$.data.inServiceArea").value(true))
                .andExpect(jsonPath("$.data.cityName").value(city.getName()));
    }

    @Test
    void pinOutsideEveryServedCityIsStillAnsweredButFlaggedOutOfArea() throws Exception {
        Customer customer = newCustomer();
        mockMvc.perform(post("/api/v1/maps/reverse-geocode")
                        .header("Authorization", "Bearer " + tokenFor(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(10.0, 10.0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inServiceArea").value(false))
                .andExpect(jsonPath("$.data.cityName").doesNotExist());
    }

    @Test
    void outOfRangeCoordinatesAreAValidationFailure() throws Exception {
        Customer customer = newCustomer();
        mockMvc.perform(post("/api/v1/maps/reverse-geocode")
                        .header("Authorization", "Bearer " + tokenFor(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(123.0, 74.80)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingCoordinatesAreAValidationFailure() throws Exception {
        Customer customer = newCustomer();
        mockMvc.perform(post("/api/v1/maps/reverse-geocode")
                        .header("Authorization", "Bearer " + tokenFor(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/maps/reverse-geocode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(34.08, 74.80)))
                .andExpect(status().isUnauthorized());
    }
}
