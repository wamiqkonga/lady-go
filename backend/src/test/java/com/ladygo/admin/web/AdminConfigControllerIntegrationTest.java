package com.ladygo.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.admin.web.dto.AdminConfigUpdateRequest;
import com.ladygo.config.domain.ConfigEntry;
import com.ladygo.config.domain.ConfigScope;
import com.ladygo.config.repository.ConfigEntryRepository;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** docs/product/admin-web-design.md section 6 - read open to ADMIN/SUPER_ADMIN, write SUPER_ADMIN-only
 *  per instruction (a live pricing/safety-threshold change takes effect immediately). */
@AutoConfigureMockMvc
class AdminConfigControllerIntegrationTest extends PersistenceTestSupport {

    private static final String SEEDED_KEY = "driver.search.radius.m";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private ConfigEntryRepository configEntries;
    @Autowired
    private AuditLogRepository auditLogs;
    @Autowired
    private ObjectMapper objectMapper;

    /** A SUPER_ADMIN token here models a fully authenticated session: the account has an authenticator enrolled and
     *  has just passed a step-up, which config edits now require (AdminStepUpIntegrationTest covers the rejections). */
    private String tokenFor(UUID userId, UserRole role) {
        if (role != UserRole.SUPER_ADMIN) {
            return jwtService.issueAccessToken(userId, role, Duration.ofMinutes(15));
        }
        User user = users.findById(userId).orElseThrow();
        user.setMfaEnabled(true);
        users.saveAndFlush(user);
        return jwtService.issueAccessToken(userId, role, Duration.ofMinutes(15), Instant.now());
    }

    private ConfigEntry seededEntry() {
        return configEntries.findByConfigKeyAndScopeAndCityIdIsNull(SEEDED_KEY, ConfigScope.GLOBAL).orElseThrow();
    }

    private ConfigEntry seededEntry(String key) {
        return configEntries.findByConfigKeyAndScopeAndCityIdIsNull(key, ConfigScope.GLOBAL).orElseThrow();
    }

    @Test
    void anAdminCanListConfig() throws Exception {
        UUID adminUserId = newUser(UserRole.ADMIN).getId();

        mockMvc.perform(get("/api/v1/admin/config")
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.configKey == '" + SEEDED_KEY + "')]").exists());
    }

    /** docs/product/admin-web-design.md section 6: the list screen shows the server-enforced bound
     *  next to a safety-relevant key's value, and null (not a guessed value) for a key with none. */
    @Test
    void listedConfigEntriesExposeServerEnforcedBounds() throws Exception {
        UUID adminUserId = newUser(UserRole.ADMIN).getId();

        mockMvc.perform(get("/api/v1/admin/config")
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.configKey == '" + SEEDED_KEY + "')].minValue").value("100"))
                .andExpect(jsonPath("$.data[?(@.configKey == '" + SEEDED_KEY + "')].maxValue").value("50000"));
    }

    @Test
    void aSuperAdminCanUpdateAConfigValueAndItIsAudited() throws Exception {
        ConfigEntry entry = seededEntry();
        UUID superAdminId = newUser(UserRole.SUPER_ADMIN).getId();

        mockMvc.perform(put("/api/v1/admin/config/" + entry.getId())
                        .header("Authorization", "Bearer " + tokenFor(superAdminId, UserRole.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("6000", "test reason"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configValue").value("6000"));

        ConfigEntry reloaded = configEntries.findById(entry.getId()).orElseThrow();
        assertThat(reloaded.getConfigValue()).isEqualTo("6000");
        assertThat(reloaded.getUpdatedBy()).isEqualTo(superAdminId);
        assertThat(auditLogs.findAll()).anySatisfy(log -> {
            if (log.getAction().equals("CONFIGURATION_CHANGED")) {
                assertThat(log.getMetadata()).containsEntry("oldValue", "5000").containsEntry("newValue", "6000");
            }
        });
    }

    /** The exact decision this endpoint is built around: config changes are SUPER_ADMIN-only, a plain
     *  ADMIN cannot write even though they can read (docs/product/admin-web-design.md section 6). */
    @Test
    void aPlainAdminCannotUpdateConfig() throws Exception {
        ConfigEntry entry = seededEntry();
        UUID adminId = newUser(UserRole.ADMIN).getId();

        mockMvc.perform(put("/api/v1/admin/config/" + entry.getId())
                        .header("Authorization", "Bearer " + tokenFor(adminId, UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("6000", "test reason"))))
                .andExpect(status().isForbidden());

        assertThat(configEntries.findById(entry.getId()).orElseThrow().getConfigValue())
                .isEqualTo("5000");
    }

    @Test
    void theCommissionRateCanBeSetAndThenClearedBackToUnsetButNoOtherKeyMayBeBlank() throws Exception {
        ConfigEntry commission = seededEntry("earnings.commission.bps");
        UUID superAdminId = newUser(UserRole.SUPER_ADMIN).getId();
        String auth = "Bearer " + tokenFor(superAdminId, UserRole.SUPER_ADMIN);

        mockMvc.perform(put("/api/v1/admin/config/" + commission.getId()).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("1500", "set rate"))))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/admin/config/" + commission.getId()).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("", "unset again"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configValue").value(""));
        assertThat(configEntries.findById(commission.getId()).orElseThrow().getConfigValue()).isEmpty();

        // the bounds on that key are unchanged
        mockMvc.perform(put("/api/v1/admin/config/" + commission.getId()).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("10001", "too high"))))
                .andExpect(status().isBadRequest());
        // any other key still rejects blank
        mockMvc.perform(put("/api/v1/admin/config/" + seededEntry().getId()).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("", "blank"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/admin/config/" + seededEntry().getId()).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("   ", "blank"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatingWithANonNumericValueForAnIntegerEntryIsRejected() throws Exception {
        ConfigEntry entry = seededEntry();
        UUID superAdminId = newUser(UserRole.SUPER_ADMIN).getId();

        mockMvc.perform(put("/api/v1/admin/config/" + entry.getId())
                        .header("Authorization", "Bearer " + tokenFor(superAdminId, UserRole.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("not-a-number", "test reason"))))
                .andExpect(status().isBadRequest());
    }

    /** The actual security-review finding: the old validator used {@code Long.parseLong}, so a value
     *  between Integer.MAX_VALUE and Long.MAX_VALUE would have passed it, then thrown at read time -
     *  the next time {@code ConfigurationService.getInt} (Integer.parseInt) read this key in
     *  production. Narrowing the validator to Integer.parseInt closes that gap. */
    @Test
    void anIntegerValueThatFitsLongButOverflowsIntIsRejected() throws Exception {
        ConfigEntry entry = seededEntry();
        UUID superAdminId = newUser(UserRole.SUPER_ADMIN).getId();
        String tooWideForInt = String.valueOf(Integer.MAX_VALUE + 1L);

        mockMvc.perform(put("/api/v1/admin/config/" + entry.getId())
                        .header("Authorization", "Bearer " + tokenFor(superAdminId, UserRole.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest(tooWideForInt, "test reason"))))
                .andExpect(status().isBadRequest());

        assertThat(configEntries.findById(entry.getId()).orElseThrow().getConfigValue()).isEqualTo("5000");
    }

    /** A value far outside the pickup-radius gate's sane bound would effectively disable the
     *  proximity gate (docs/api/ride-lifecycle.md's safety-review finding on this exact key) - the
     *  bare type check alone (does it parse as an integer?) would have let this through. */
    @Test
    void anOutOfRangeValueForASafetyRelevantKeyIsRejected() throws Exception {
        ConfigEntry entry = seededEntry("ride.gate.pickup_radius.m");
        UUID superAdminId = newUser(UserRole.SUPER_ADMIN).getId();

        mockMvc.perform(put("/api/v1/admin/config/" + entry.getId())
                        .header("Authorization", "Bearer " + tokenFor(superAdminId, UserRole.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("999999", "test reason"))))
                .andExpect(status().isBadRequest());

        assertThat(configEntries.findById(entry.getId()).orElseThrow().getConfigValue()).isEqualTo("200");
    }

    @Test
    void theMatchingLocationMaxAgeIsSeededAtOneTwentyAndBoundedToTenThroughThirtySixHundred() throws Exception {
        ConfigEntry entry = seededEntry("matching.driver.location.max.age.seconds");
        UUID superAdminId = newUser(UserRole.SUPER_ADMIN).getId();
        assertThat(entry.getConfigValue()).isEqualTo("120");

        for (String bad : new String[] {"9", "3601"}) {
            mockMvc.perform(put("/api/v1/admin/config/" + entry.getId())
                            .header("Authorization", "Bearer " + tokenFor(superAdminId, UserRole.SUPER_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest(bad, "test reason"))))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(put("/api/v1/admin/config/" + entry.getId())
                        .header("Authorization", "Bearer " + tokenFor(superAdminId, UserRole.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("10", "test reason"))))
                .andExpect(status().isOk());
    }

    @Test
    void aValueWithinTheSafetyBoundIsAccepted() throws Exception {
        ConfigEntry entry = seededEntry("ride.pin.max.attempts");
        UUID superAdminId = newUser(UserRole.SUPER_ADMIN).getId();

        mockMvc.perform(put("/api/v1/admin/config/" + entry.getId())
                        .header("Authorization", "Bearer " + tokenFor(superAdminId, UserRole.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("10", "test reason"))))
                .andExpect(status().isOk());

        assertThat(configEntries.findById(entry.getId()).orElseThrow().getConfigValue()).isEqualTo("10");
    }

    @Test
    void aDriverCannotReadOrWriteConfig() throws Exception {
        var driver = newDriver(newCity());
        ConfigEntry entry = seededEntry();

        mockMvc.perform(get("/api/v1/admin/config")
                        .header("Authorization", "Bearer " + tokenFor(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/admin/config/" + entry.getId())
                        .header("Authorization", "Bearer " + tokenFor(driver.getUserId(), UserRole.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("6000", "test reason"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void supportContactNumbersCanBeSetAndClearedAndBadFormatsAreRejected() throws Exception {
        UUID superAdminId = newUser(UserRole.SUPER_ADMIN).getId();
        String auth = "Bearer " + tokenFor(superAdminId, UserRole.SUPER_ADMIN);
        for (String key : new String[] {"support.phone", "support.whatsapp"}) {
            ConfigEntry entry = seededEntry(key);
            assertThat(entry.getConfigValue()).isEmpty();
            for (String bad : new String[] {"abc12345678", "+1234567", "1234567890123456", "+9198 7654 3210",
                    "++919876543210", "98765-43210", " 919876543210", "919876543210\n"}) {
                mockMvc.perform(put("/api/v1/admin/config/" + entry.getId()).header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest(bad, "bad"))))
                        .andExpect(status().isBadRequest());
            }
            assertThat(configEntries.findById(entry.getId()).orElseThrow().getConfigValue()).isEmpty();
            for (String good : new String[] {"+919876543210", "12345678", "123456789012345"}) {
                mockMvc.perform(put("/api/v1/admin/config/" + entry.getId()).header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest(good, "set"))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.configValue").value(good));
            }
            mockMvc.perform(put("/api/v1/admin/config/" + entry.getId()).header("Authorization", auth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("  ", "clear"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.configValue").value(""));
            assertThat(configEntries.findById(entry.getId()).orElseThrow().getConfigValue()).isEmpty();
        }
    }
}
