package com.ladygo.auth.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.notifications.email.LoggingEmailSender;
import com.ladygo.testsupport.PersistenceTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP layer: the endpoints are public, and errors carry the codes the apps switch on. */
@AutoConfigureMockMvc
class CustomerAuthControllerIntegrationTest extends PersistenceTestSupport {

    @Autowired
    private MockMvc mockMvc;

    private void send(String path, String json, int expectedStatus) throws Exception {
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void aWholeRegistrationAndLoginWorksWithoutAnyAuthorizationHeader() throws Exception {
        String email = "r-" + UUID.randomUUID() + "@example.test";
        String phone = "+919388800001";
        send("/api/v1/auth/customer/register/otp", "{\"phone\":\"" + phone + "\",\"email\":\"" + email + "\"}", 200);
        String code = LoggingEmailSender.lastCodeFor(email);

        mockMvc.perform(post("/api/v1/auth/customer/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"email\":\"" + email + "\",\"code\":\"" + code
                                + "\",\"password\":\"Sunrise2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());

        send("/api/v1/auth/customer/login", "{\"identifier\":\"" + email + "\",\"password\":\"Sunrise2026\"}", 200);
        send("/api/v1/auth/customer/login", "{\"identifier\":\"" + email + "\",\"password\":\"Wrong12345\"}", 401);
    }

    @Test
    void registeringAnExistingEmailIsAConflictWithItsOwnCode() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@example.test";
        String phone = "+919388800002";
        send("/api/v1/auth/customer/register/otp", "{\"phone\":\"" + phone + "\",\"email\":\"" + email + "\"}", 200);
        String code = LoggingEmailSender.lastCodeFor(email);
        send("/api/v1/auth/customer/register", "{\"phone\":\"" + phone + "\",\"email\":\"" + email + "\",\"code\":\""
                + code + "\",\"password\":\"Sunrise2026\"}", 200);

        mockMvc.perform(post("/api/v1/auth/customer/register/otp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"+919388800003\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_EXISTS"));
    }

    @Test
    void emailAndPhoneAreBothRequiredAndValidated() throws Exception {
        send("/api/v1/auth/customer/register/otp", "{\"phone\":\"+919388800004\"}", 400);
        send("/api/v1/auth/customer/register/otp", "{\"email\":\"a@example.test\"}", 400);
        send("/api/v1/auth/customer/register/otp", "{\"phone\":\"9388800004\",\"email\":\"a@example.test\"}", 400);
        send("/api/v1/auth/customer/register/otp", "{\"phone\":\"+919388800004\",\"email\":\"not-an-email\"}", 400);
    }

    @Test
    void forgotPasswordAnswersTheSameForAnUnknownEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/customer/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody-" + UUID.randomUUID() + "@example.test\"}"))
                .andExpect(status().isOk());
    }
}
