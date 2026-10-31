package com.ladygo.notifications.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import com.ladygo.notifications.domain.AppType;
import com.ladygo.notifications.repository.DeviceTokenRepository;
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

@AutoConfigureMockMvc
class DeviceControllerIntegrationTest extends PersistenceTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private DeviceTokenRepository devices;

    private String tokenFor(UUID userId, UserRole role) {
        return jwtService.issueAccessToken(userId, role, Duration.ofMinutes(15));
    }

    private void register(String bearer, String deviceToken) throws Exception {
        mockMvc.perform(post("/api/v1/notifications/devices")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + deviceToken + "\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void registrationRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"t\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theAppIsDerivedFromTheCallersRoleAndRegisteringTwiceIsIdempotent() throws Exception {
        Customer rider = newCustomer();
        String bearer = tokenFor(rider.getUserId(), UserRole.CUSTOMER);

        register(bearer, "rider-device");
        register(bearer, "rider-device");

        var mine = devices.findByUserId(rider.getUserId());
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getApp()).isEqualTo(AppType.CUSTOMER);

        City city = newCity();
        Driver driver = newDriver(city);
        register(tokenFor(driver.getUserId(), UserRole.DRIVER), "driver-device");
        assertThat(devices.findByToken("driver-device")).get().extracting(d -> d.getApp()).isEqualTo(AppType.DRIVER);
    }

    @Test
    void aTokenMovesToWhoeverSignsInOnThatPhoneNext() throws Exception {
        Customer first = newCustomer();
        Customer second = newCustomer();

        register(tokenFor(first.getUserId(), UserRole.CUSTOMER), "shared-phone");
        register(tokenFor(second.getUserId(), UserRole.CUSTOMER), "shared-phone");

        assertThat(devices.findByUserId(first.getUserId())).isEmpty();
        assertThat(devices.findByUserId(second.getUserId())).hasSize(1);
    }

    @Test
    void aUserKeepsOnlyHerFiveNewestDevices() throws Exception {
        Customer rider = newCustomer();
        String bearer = tokenFor(rider.getUserId(), UserRole.CUSTOMER);
        for (int i = 0; i < 7; i++) {
            register(bearer, "device-" + i);
        }
        assertThat(devices.findByUserId(rider.getUserId())).hasSize(5);
    }

    @Test
    void signingOutRemovesOnlyTheCallersOwnToken() throws Exception {
        Customer owner = newCustomer();
        Customer other = newCustomer();
        register(tokenFor(owner.getUserId(), UserRole.CUSTOMER), "owned");

        mockMvc.perform(delete("/api/v1/notifications/devices")
                        .header("Authorization", "Bearer " + tokenFor(other.getUserId(), UserRole.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"owned\"}"))
                .andExpect(status().isOk());
        assertThat(devices.findByToken("owned")).isPresent();

        mockMvc.perform(delete("/api/v1/notifications/devices")
                        .header("Authorization", "Bearer " + tokenFor(owner.getUserId(), UserRole.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"owned\"}"))
                .andExpect(status().isOk());
        assertThat(devices.findByToken("owned")).isEmpty();
    }

    @Test
    void anOversizedOrBlankTokenIsAValidationFailure() throws Exception {
        Customer rider = newCustomer();
        String bearer = tokenFor(rider.getUserId(), UserRole.CUSTOMER);
        for (String bad : new String[] {"", "x".repeat(501)}) {
            mockMvc.perform(post("/api/v1/notifications/devices")
                            .header("Authorization", "Bearer " + bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"" + bad + "\",\"platform\":\"ANDROID\"}"))
                    .andExpect(status().isBadRequest());
        }
    }
}
