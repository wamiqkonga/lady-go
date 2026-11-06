package com.ladygo.drivers.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** PUT/GET of a captain's own full name, PUT /api/v1/drivers/me/name and GET /api/v1/drivers/me. */
@AutoConfigureMockMvc
class DriverNameControllerIntegrationTest extends PersistenceTestSupport {

    private static final String NAME = "/api/v1/drivers/me/name";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private AuditLogRepository auditLogs;

    private String token(UUID userId, UserRole role) {
        return "Bearer " + jwtService.issueAccessToken(userId, role, Duration.ofMinutes(15));
    }

    /** A captain who has not given a name yet. */
    private Driver nameless() {
        Driver driver = newDriver(newCity());
        User user = users.findById(driver.getUserId()).orElseThrow();
        user.setName(null);
        users.saveAndFlush(user);
        return driver;
    }

    private ResultActions putName(Driver driver, String body) throws Exception {
        return mockMvc.perform(put(NAME).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", token(driver.getUserId(), UserRole.DRIVER)));
    }

    private List<AuditLog> nameAudits(Driver driver) {
        return auditLogs.findAll().stream()
                .filter(a -> a.getTargetId().equals(driver.getUserId().toString()) && a.getAction().startsWith("DRIVER_NAME"))
                .toList();
    }

    @Test
    void profileShowsNoNameUntilOneIsSet() throws Exception {
        Driver driver = nameless();
        mockMvc.perform(get("/api/v1/drivers/me").header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").doesNotExist());
    }

    @Test
    void settingTheNameStoresItTrimmedAndTheProfileReturnsIt() throws Exception {
        Driver driver = nameless();
        putName(driver, "{\"fullName\":\"  Aisha    Khan \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Aisha Khan"))
                .andExpect(jsonPath("$.data.id").value(driver.getId().toString()));

        assertThat(users.findById(driver.getUserId()).orElseThrow().getName()).isEqualTo("Aisha Khan");
        mockMvc.perform(get("/api/v1/drivers/me").header("Authorization", token(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(jsonPath("$.data.fullName").value("Aisha Khan"));
    }

    @Test
    void urduAndHindiNamesAreAccepted() throws Exception {
        Driver driver = nameless();
        putName(driver, "{\"fullName\":\"عائشہ خان\"}").andExpect(status().isOk());
        putName(driver, "{\"fullName\":\"आयशा खान\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("आयशा खान"));
    }

    @Test
    void invalidNamesAreRejectedWith400AndNothingIsStored() throws Exception {
        Driver driver = nameless();
        for (String bad : List.of("A", "Aisha 7", "<b>Aisha</b>", "", " ", "a".repeat(81))) {
            putName(driver, "{\"fullName\":\"" + bad + "\"}").andExpect(status().isBadRequest());
        }
        putName(driver, "{}").andExpect(status().isBadRequest());
        assertThat(users.findById(driver.getUserId()).orElseThrow().getName()).isNull();
        assertThat(nameAudits(driver)).isEmpty();
    }

    @Test
    void theFirstSetAndEveryChangeAreAuditedWithoutTheNameOrPhone() throws Exception {
        Driver driver = nameless();
        putName(driver, "{\"fullName\":\"Aisha Khan\"}").andExpect(status().isOk());
        putName(driver, "{\"fullName\":\"Aisha Khan\"}").andExpect(status().isOk()); // unchanged: no new entry
        putName(driver, "{\"fullName\":\"Aisha Dar\"}").andExpect(status().isOk());

        List<AuditLog> audits = nameAudits(driver);
        assertThat(audits).extracting(AuditLog::getAction)
                .containsExactlyInAnyOrder("DRIVER_NAME_SET", "DRIVER_NAME_CHANGED");
        User user = users.findById(driver.getUserId()).orElseThrow();
        for (AuditLog entry : audits) {
            assertThat(entry.getActorId()).isEqualTo(driver.getUserId());
            assertThat(entry.getTargetType()).isEqualTo("users");
            String everything = entry.toString() + entry.getMetadata() + entry.getAction();
            assertThat(everything).doesNotContain("Aisha").doesNotContain("Khan").doesNotContain("Dar")
                    .doesNotContain(user.getPhone());
        }
    }

    @Test
    void afterAReviewDecisionTheNameCannotBeChangedButAFirstNameCanStillBeGiven() throws Exception {
        Driver decided = nameless();
        putName(decided, "{\"fullName\":\"Aisha Khan\"}").andExpect(status().isOk());
        decided.setVerificationStatus(com.ladygo.drivers.domain.DriverVerificationStatus.APPROVED);
        drivers.saveAndFlush(decided);

        putName(decided, "{\"fullName\":\"Someone Else\"}").andExpect(status().isBadRequest());
        putName(decided, "{\"fullName\":\"Aisha Khan\"}").andExpect(status().isOk()); // unchanged: harmless
        assertThat(users.findById(decided.getUserId()).orElseThrow().getName()).isEqualTo("Aisha Khan");

        Driver existing = nameless(); // approved before names existed
        existing.setVerificationStatus(com.ladygo.drivers.domain.DriverVerificationStatus.APPROVED);
        drivers.saveAndFlush(existing);
        putName(existing, "{\"fullName\":\"Zainab Dar\"}").andExpect(status().isOk());
    }

    @Test
    void onlyADriverMayUseIt() throws Exception {
        String body = "{\"fullName\":\"Aisha Khan\"}";
        mockMvc.perform(put(NAME).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", token(newCustomer().getUserId(), UserRole.CUSTOMER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(NAME).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSignedInUserWithoutADriverProfileGets404() throws Exception {
        UUID userId = newUser(UserRole.DRIVER).getId();
        mockMvc.perform(put(NAME).contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Aisha Khan\"}")
                        .header("Authorization", token(userId, UserRole.DRIVER)))
                .andExpect(status().isNotFound());
    }
}
