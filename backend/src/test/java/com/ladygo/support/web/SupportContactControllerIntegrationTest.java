package com.ladygo.support.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MockMvc;

/** GET /api/v1/support/contact: any authenticated role, null per field when not configured. */
@AutoConfigureMockMvc
class SupportContactControllerIntegrationTest extends PersistenceTestSupport {

    private static final String URL = "/api/v1/support/contact";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private ConfigEntryRepository configEntries;

    private String auth(UserRole role) {
        UUID userId = newUser(role).getId();
        return "Bearer " + jwtService.issueAccessToken(userId, role, Duration.ofMinutes(15));
    }

    private void set(String key, String value) {
        ConfigEntry entry = configEntries.findByConfigKeyAndScopeAndCityIdIsNull(key, ConfigScope.GLOBAL).orElseThrow();
        entry.setConfigValue(value);
        configEntries.save(entry);
    }

    @Test
    void returnsNullsWhenNothingIsConfigured() throws Exception {
        mockMvc.perform(get(URL).header("Authorization", auth(UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value((Object) null))
                .andExpect(jsonPath("$.data.whatsapp").value((Object) null));
    }

    @Test
    void returnsTheConfiguredValuesAndTreatsBlankAsNull() throws Exception {
        set("support.phone", "+919876543210");
        set("support.whatsapp", "   ");
        mockMvc.perform(get(URL).header("Authorization", auth(UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("+919876543210"))
                .andExpect(jsonPath("$.data.whatsapp").value((Object) null));
    }

    @Test
    void everyRoleCanRead() throws Exception {
        set("support.whatsapp", "919876543210");
        for (UserRole role : UserRole.values()) {
            mockMvc.perform(get(URL).header("Authorization", auth(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.whatsapp").value("919876543210"));
        }
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }
}
