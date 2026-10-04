package com.ladygo.auth.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.testsupport.IntegrationTest;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.repository.UserRepository;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class AdminAuthControllerIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(4000);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void loginEndpointIsPublicButRejectsUnknownCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@ladygo.test\",\"password\":\"whatever12345\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void enrolmentEndpointsRequireAnEnrolmentToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/password/change")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"newpassword123\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/admin/mfa/enroll"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidEmailFailsValidationBeforeHittingTheService() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"whatever12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /**
     * Adversarial review finding (HIGH): an enrolment-scoped token satisfied the blanket
     * anyRequest().authenticated() rule and could reach endpoints carrying no role-specific
     * @PreAuthorize, contradicting auth-design.md section 3.2's claim that it is "only accepted by
     * the three enrolment endpoints... nothing else." /auth/logout is exactly such an endpoint.
     */
    @Test
    void enrolmentTokenCannotBeUsedOnNonEnrolmentEndpoints() throws Exception {
        String email = "pending" + SEQ.incrementAndGet() + "@ladygo.test";
        User staff = new User();
        staff.setPhone("+9193" + String.format("%08d", SEQ.incrementAndGet()));
        staff.setEmail(email);
        staff.setRole(UserRole.ADMIN);
        staff.setPasswordHash(passwordEncoder.encode("InitialPass123!"));
        staff.setMustChangePassword(true);
        staff.setMfaEnabled(false);
        users.saveAndFlush(staff);

        String loginResponse = mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"InitialPass123!\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String enrolmentToken = com.jayway.jsonpath.JsonPath.read(loginResponse, "$.data.enrolmentToken");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + enrolmentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"anything\"}"))
                .andExpect(status().isUnauthorized());
    }
}
