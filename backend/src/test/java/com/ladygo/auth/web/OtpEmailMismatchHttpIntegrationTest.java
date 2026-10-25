package com.ladygo.auth.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.auth.service.OtpLoginService;
import com.ladygo.notifications.email.LoggingEmailSender;
import com.ladygo.testsupport.IntegrationTest;
import com.ladygo.users.domain.UserRole;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** What the driver's browser receives when the phone and email do not go together (email channel). */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "ladygo.otp.channel=email")
class OtpEmailMismatchHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OtpLoginService otpLoginService;

    @Test
    void aMismatchedPairGetsA409WithAMessageTheWebsiteCanShow() throws Exception {
        String phoneA = "+919500990001";
        String email = "driver-" + UUID.randomUUID() + "@example.test";
        otpLoginService.requestOtp(phoneA, OtpPurpose.LOGIN, UserRole.DRIVER, "1.2.3.4", null, email);
        otpLoginService.verifyOtp(phoneA, LoggingEmailSender.lastCodeFor(email), OtpPurpose.LOGIN,
                UserRole.DRIVER, "device-1");

        String body = "{\"phone\":\"+919500990002\",\"purpose\":\"LOGIN\",\"intendedRole\":\"DRIVER\",\"email\":\""
                + email + "\"}";
        mockMvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("OTP_EMAIL_MISMATCH"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("same phone number and email")));
    }
}
