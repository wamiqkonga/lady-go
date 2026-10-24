package com.ladygo.auth.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.notifications.sms.LoggingSmsSender;
import com.ladygo.testsupport.IntegrationTest;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.repository.UserRepository;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class AuthControllerIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(3000);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository users;

    private static String nextPhone() {
        return "+9195" + String.format("%08d", SEQ.incrementAndGet());
    }

    @Test
    void otpRequestIsPublicAndReturnsAGenericMessage() throws Exception {
        String phone = nextPhone();
        mockMvc.perform(post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"purpose\":\"LOGIN\",\"intendedRole\":\"CUSTOMER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.message").value("If this number is valid, a code was sent."));
    }

    @Test
    void fullOtpLoginIssuesTokens() throws Exception {
        String phone = nextPhone();
        mockMvc.perform(post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"purpose\":\"LOGIN\",\"intendedRole\":\"CUSTOMER\"}"))
                .andExpect(status().isOk());

        String code = LoggingSmsSender.lastCodeFor(phone);

        mockMvc.perform(post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code
                                + "\",\"purpose\":\"LOGIN\",\"intendedRole\":\"CUSTOMER\",\"deviceId\":\"dev-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.refreshToken").exists());
    }

    @Test
    void wrongCodeIsRejectedWithValidationFailed() throws Exception {
        String phone = nextPhone();
        mockMvc.perform(post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"purpose\":\"LOGIN\",\"intendedRole\":\"CUSTOMER\"}"));

        mockMvc.perform(post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone
                                + "\",\"code\":\"000000\",\"purpose\":\"LOGIN\",\"intendedRole\":\"CUSTOMER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void intendedRoleOutsideCustomerOrDriverIsRejected() throws Exception {
        String phone = nextPhone();
        mockMvc.perform(post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"purpose\":\"LOGIN\",\"intendedRole\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void refreshAndThenReuseOfOldTokenIsRejected() throws Exception {
        String phone = nextPhone();
        mockMvc.perform(post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"purpose\":\"LOGIN\",\"intendedRole\":\"DRIVER\"}"));
        String code = LoggingSmsSender.lastCodeFor(phone);

        String verifyResponse = mockMvc.perform(post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code
                                + "\",\"purpose\":\"LOGIN\",\"intendedRole\":\"DRIVER\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String refreshToken = com.jayway.jsonpath.JsonPath.read(verifyResponse, "$.data.refreshToken");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refreshToken").exists());

        // The old (now-rotated) token is reuse - rejected.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** OTP request must not reveal whether a phone number is already registered - same status and
     *  same generic body either way (docs/development/sms-provider-setup.md, "OTP send must not
     *  reveal whether a phone number is registered"). */
    @Test
    void otpRequestLooksIdenticalForARegisteredAndAnUnregisteredPhone() throws Exception {
        String registeredPhone = nextPhone();
        User existing = new User();
        existing.setPhone(registeredPhone);
        existing.setRole(UserRole.CUSTOMER);
        users.saveAndFlush(existing);

        String unregisteredPhone = nextPhone();

        String registeredResponse = mockMvc.perform(post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + registeredPhone + "\",\"purpose\":\"LOGIN\",\"intendedRole\":\"CUSTOMER\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String unregisteredResponse = mockMvc.perform(post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + unregisteredPhone + "\",\"purpose\":\"LOGIN\",\"intendedRole\":\"CUSTOMER\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Compare only the semantically meaningful fields - the envelope's own `timestamp` legitimately
        // differs between the two calls and isn't part of what "identical response" means here.
        Boolean registeredSuccess = com.jayway.jsonpath.JsonPath.read(registeredResponse, "$.success");
        Boolean unregisteredSuccess = com.jayway.jsonpath.JsonPath.read(unregisteredResponse, "$.success");
        String registeredMessage = com.jayway.jsonpath.JsonPath.read(registeredResponse, "$.data.message");
        String unregisteredMessage = com.jayway.jsonpath.JsonPath.read(unregisteredResponse, "$.data.message");

        org.assertj.core.api.Assertions.assertThat(registeredSuccess).isEqualTo(unregisteredSuccess);
        org.assertj.core.api.Assertions.assertThat(registeredMessage).isEqualTo(unregisteredMessage);
        org.assertj.core.api.Assertions.assertThat(registeredMessage).isEqualTo("If this number is valid, a code was sent.");
    }
}
