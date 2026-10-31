package com.ladygo.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ladygo.config.domain.ConfigEntry;
import com.ladygo.config.domain.ConfigScope;
import com.ladygo.config.repository.ConfigEntryRepository;
import com.ladygo.security.crypto.MfaSecretCipher;
import com.ladygo.security.crypto.TotpService;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** docs/security/admin-stepup-lockout-recovery-design.md section 2: destructive admin actions need a
 *  TOTP check newer than the step-up window, enforced server-side. The step-up time travels inside the
 *  access token itself, so it is per-session and survives no refresh. */
@AutoConfigureMockMvc
class AdminStepUpIntegrationTest extends PersistenceTestSupport {

    private static final String SEEDED_KEY = "driver.search.radius.m";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private ConfigEntryRepository configEntries;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TotpService totpService;
    @Autowired private MfaSecretCipher mfaSecretCipher;

    private String secret;

    private User enrolledSuperAdmin() {
        User user = newUser(UserRole.SUPER_ADMIN);
        secret = totpService.generateSecret();
        user.setMfaSecretEnc(mfaSecretCipher.encrypt(secret));
        user.setMfaEnabled(true);
        return users.saveAndFlush(user);
    }

    private String token(User user, Instant stepUpAt) {
        return jwtService.issueAccessToken(user.getId(), user.getRole(), Duration.ofMinutes(10), stepUpAt);
    }

    private String currentCode() {
        return totpService.generateCode(secret, totpService.currentStep(Instant.now()));
    }

    private ConfigEntry entry() {
        return configEntries.findByConfigKeyAndScopeAndCityIdIsNull(SEEDED_KEY, ConfigScope.GLOBAL).orElseThrow();
    }

    private org.springframework.test.web.servlet.ResultActions putConfig(ConfigEntry entry, String bearer)
            throws Exception {
        return mockMvc.perform(put("/api/v1/admin/config/" + entry.getId())
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"6000\",\"reason\":\"test\"}"));
    }

    @Test
    void configEditWithoutAStepUpIsRejectedWithStepUpRequired() throws Exception {
        User admin = enrolledSuperAdmin();
        ConfigEntry entry = entry();

        putConfig(entry, token(admin, null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));

        assertThat(configEntries.findById(entry.getId()).orElseThrow().getConfigValue()).isEqualTo("5000");
    }

    @Test
    void aStepUpOlderThanTheWindowIsRejected() throws Exception {
        User admin = enrolledSuperAdmin();

        putConfig(entry(), token(admin, Instant.now().minus(Duration.ofMinutes(6))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));
    }

    @Test
    void aFreshStepUpAllowsTheConfigEdit() throws Exception {
        User admin = enrolledSuperAdmin();
        ConfigEntry entry = entry();

        putConfig(entry, token(admin, Instant.now().minusSeconds(30))).andExpect(status().isOk());

        assertThat(configEntries.findById(entry.getId()).orElseThrow().getConfigValue()).isEqualTo("6000");
    }

    /** Destructive actions are never allowed without MFA, even though login MFA can be switched off for the
     *  pilot: a step-up claim alone is not enough if the account has no second factor. */
    @Test
    void anAdminWithoutMfaEnrolmentCannotEditEvenWithAStepUpClaim() throws Exception {
        User admin = newUser(UserRole.SUPER_ADMIN);

        putConfig(entry(), token(admin, Instant.now()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MFA_ENROLMENT_REQUIRED"));
    }

    @Test
    void mfaResetAlsoNeedsAFreshStepUp() throws Exception {
        User admin = enrolledSuperAdmin();
        User target = newUser(UserRole.ADMIN);

        mockMvc.perform(post("/api/v1/admin/users/" + target.getId() + "/mfa/reset")
                        .header("Authorization", "Bearer " + token(admin, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));
    }

    @Test
    void stepUpWithAValidCodeReturnsATokenThatPassesTheGuard() throws Exception {
        User admin = enrolledSuperAdmin();
        ConfigEntry entry = entry();

        String body = mockMvc.perform(post("/api/v1/auth/admin/step-up")
                        .header("Authorization", "Bearer " + token(admin, null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + currentCode() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        String upgraded = json.at("/data/accessToken").asText();

        putConfig(entry, upgraded).andExpect(status().isOk());
    }

    @Test
    void stepUpWithAWrongCodeIsRejectedAndGrantsNothing() throws Exception {
        User admin = enrolledSuperAdmin();

        mockMvc.perform(post("/api/v1/auth/admin/step-up")
                        .header("Authorization", "Bearer " + token(admin, null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTotpCodeCannotBeReplayedForAStepUp() throws Exception {
        User admin = enrolledSuperAdmin();
        String code = currentCode();

        mockMvc.perform(post("/api/v1/auth/admin/step-up")
                        .header("Authorization", "Bearer " + token(admin, null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/admin/step-up")
                        .header("Authorization", "Bearer " + token(admin, null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void stepUpRequiresAnAuthenticatedStaffToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/step-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** A logged-in admin with no second factor can enrol one from inside the portal; proving possession of the
     *  new secret also counts as a step-up, so the first protected action does not ask twice. */
    @Test
    void anAdminWithoutMfaCanSelfEnrolAndGetsAFreshStepUpToken() throws Exception {
        User admin = newUser(UserRole.SUPER_ADMIN);
        String bearer = token(admin, null);

        String startBody = mockMvc.perform(post("/api/v1/auth/admin/mfa/self/enroll")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.secret").exists())
                .andReturn().getResponse().getContentAsString();
        String newSecret = objectMapper.readTree(startBody).at("/data/secret").asText();
        String code = totpService.generateCode(newSecret, totpService.currentStep(Instant.now()));

        String confirmBody = mockMvc.perform(post("/api/v1/auth/admin/mfa/self/enroll/confirm")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String upgraded = objectMapper.readTree(confirmBody).at("/data/accessToken").asText();

        putConfig(entry(), upgraded).andExpect(status().isOk());
    }

    /** If an attacker steals a session, they must not be able to swap in their own authenticator. */
    @Test
    void anAdminWhoAlreadyHasMfaCannotRestartSelfEnrolment() throws Exception {
        User admin = enrolledSuperAdmin();

        mockMvc.perform(post("/api/v1/auth/admin/mfa/self/enroll")
                        .header("Authorization", "Bearer " + token(admin, Instant.now())))
                .andExpect(status().isForbidden());
    }
}
