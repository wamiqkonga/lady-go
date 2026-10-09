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
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
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

    private String tokenFor(UUID userId, UserRole role) {
        return jwtService.issueAccessToken(userId, role, Duration.ofMinutes(15));
    }

    private ConfigEntry seededEntry() {
        return configEntries.findByConfigKeyAndScopeAndCityIdIsNull(SEEDED_KEY, ConfigScope.GLOBAL).orElseThrow();
    }

    @Test
    void anAdminCanListConfig() throws Exception {
        UUID adminUserId = newUser(UserRole.ADMIN).getId();

        mockMvc.perform(get("/api/v1/admin/config")
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.configKey == '" + SEEDED_KEY + "')]").exists());
    }

    @Test
    void aSuperAdminCanUpdateAConfigValueAndItIsAudited() throws Exception {
        ConfigEntry entry = seededEntry();
        UUID superAdminId = newUser(UserRole.SUPER_ADMIN).getId();

        mockMvc.perform(put("/api/v1/admin/config/" + entry.getId())
                        .header("Authorization", "Bearer " + tokenFor(superAdminId, UserRole.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("6000"))))
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
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("6000"))))
                .andExpect(status().isForbidden());

        assertThat(configEntries.findById(entry.getId()).orElseThrow().getConfigValue())
                .isEqualTo("5000");
    }

    @Test
    void updatingWithANonNumericValueForAnIntegerEntryIsRejected() throws Exception {
        ConfigEntry entry = seededEntry();
        UUID superAdminId = newUser(UserRole.SUPER_ADMIN).getId();

        mockMvc.perform(put("/api/v1/admin/config/" + entry.getId())
                        .header("Authorization", "Bearer " + tokenFor(superAdminId, UserRole.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("not-a-number"))))
                .andExpect(status().isBadRequest());
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
                        .content(objectMapper.writeValueAsString(new AdminConfigUpdateRequest("6000"))))
                .andExpect(status().isForbidden());
    }
}
