package com.ladygo.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.locations.domain.City;
import com.ladygo.security.crypto.MfaSecretCipher;
import com.ladygo.security.crypto.TotpService;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Admin Drivers screen API: list/search/detail for SUPPORT and above; suspend/activate for ADMIN and above
 *  with a mandatory reason, a fresh step-up and an audit row. */
@AutoConfigureMockMvc
class AdminDriverControllerIntegrationTest extends PersistenceTestSupport {

    private static final String BASE = "/api/v1/admin/drivers";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private TotpService totpService;
    @Autowired private MfaSecretCipher mfaSecretCipher;

    private User staff(UserRole role) {
        User user = newUser(role);
        user.setMfaSecretEnc(mfaSecretCipher.encrypt(totpService.generateSecret()));
        user.setMfaEnabled(true);
        return users.saveAndFlush(user);
    }

    private String fresh(User user) {
        return jwtService.issueAccessToken(user.getId(), user.getRole(), Duration.ofMinutes(10), Instant.now());
    }

    private String stale(User user) {
        return jwtService.issueAccessToken(user.getId(), user.getRole(), Duration.ofMinutes(10), null);
    }

    private Driver approvedDriver() {
        City city = newCity();
        Driver driver = newDriver(city);
        driver.setVerificationStatus(DriverVerificationStatus.APPROVED);
        driver.setAvailabilityStatus(DriverAvailability.ONLINE);
        return drivers.saveAndFlush(driver);
    }

    private ResultActions suspend(UUID id, String bearer, String body) throws Exception {
        return mockMvc.perform(post(BASE + "/" + id + "/suspend")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions activate(UUID id, String bearer, String body) throws Exception {
        return mockMvc.perform(post(BASE + "/" + id + "/activate")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private List<AuditLog> auditFor(UUID driverId, String action) {
        return auditLogs.findAll().stream()
                .filter(a -> driverId.toString().equals(a.getTargetId()) && action.equals(a.getAction()))
                .toList();
    }

    // ---- list / detail -------------------------------------------------------------------------

    @Test
    void supportCanListDriversWithMaskedPhone() throws Exception {
        Driver driver = approvedDriver();
        User phoneOwner = users.findById(driver.getUserId()).orElseThrow();
        User support = newUser(UserRole.SUPPORT);

        mockMvc.perform(get(BASE).param("q", phoneOwner.getName()).header("Authorization", "Bearer " + stale(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(driver.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].phoneMasked").value(
                        org.hamcrest.Matchers.endsWith(phoneOwner.getPhone().substring(phoneOwner.getPhone().length() - 4))))
                .andExpect(jsonPath("$.data.content[0].phoneMasked").value(org.hamcrest.Matchers.startsWith("*")));
    }

    @Test
    void listIsRoleGated() throws Exception {
        User driverUser = newUser(UserRole.DRIVER);
        User customer = newUser(UserRole.CUSTOMER);
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + stale(driverUser))).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + stale(customer))).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void searchMatchesByIdAndPhoneFragmentAndFiltersByVerificationStatus() throws Exception {
        Driver approved = approvedDriver();
        Driver pending = newDriver(newCity());
        pending.setVerificationStatus(DriverVerificationStatus.PENDING_REVIEW);
        drivers.saveAndFlush(pending);
        User admin = newUser(UserRole.ADMIN);
        String bearer = "Bearer " + stale(admin);

        mockMvc.perform(get(BASE).param("q", approved.getId().toString()).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(approved.getId().toString()));

        String phone = users.findById(pending.getUserId()).orElseThrow().getPhone();
        mockMvc.perform(get(BASE).param("q", phone.substring(phone.length() - 7)).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.id == '" + pending.getId() + "')]").exists());

        mockMvc.perform(get(BASE).param("verificationStatus", "PENDING_REVIEW")
                        .param("q", pending.getId().toString()).header("Authorization", bearer))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(get(BASE).param("verificationStatus", "SUSPENDED")
                        .param("q", pending.getId().toString()).header("Authorization", bearer))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void searchTreatsPercentAsLiteralNotWildcard() throws Exception {
        approvedDriver();
        mockMvc.perform(get(BASE).param("q", "%%%").header("Authorization", "Bearer " + stale(newUser(UserRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void detailShowsProfileEarningsAndReviewLinkTarget() throws Exception {
        Driver driver = approvedDriver();
        User support = newUser(UserRole.SUPPORT);

        mockMvc.perform(get(BASE + "/" + driver.getId()).header("Authorization", "Bearer " + stale(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(driver.getId().toString()))
                .andExpect(jsonPath("$.data.verificationStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.availabilityStatus").value("ONLINE"))
                .andExpect(jsonPath("$.data.earnings.rides").value(0))
                .andExpect(jsonPath("$.data.recentRides").isArray())
                .andExpect(jsonPath("$.data.documents.reviewPath").value("/driver-verification/" + driver.getId()));
    }

    @Test
    void detailOfUnknownDriverIs404() throws Exception {
        mockMvc.perform(get(BASE + "/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + stale(newUser(UserRole.SUPPORT))))
                .andExpect(status().isNotFound());
    }

    // ---- suspend -------------------------------------------------------------------------------

    @Test
    void adminSuspendsWithReasonAndStepUpForcingDriverOfflineAndWritesAudit() throws Exception {
        Driver driver = approvedDriver();
        User admin = staff(UserRole.ADMIN);

        suspend(driver.getId(), fresh(admin), "{\"reason\":\"Complaint verified by support\"}")
                .andExpect(status().isOk());

        flushAndClear();
        Driver after = drivers.findById(driver.getId()).orElseThrow();
        assertThat(after.getAvailabilityStatus()).isEqualTo(DriverAvailability.SUSPENDED);
        assertThat(after.getVerificationStatus()).isEqualTo(DriverVerificationStatus.SUSPENDED);
        List<AuditLog> rows = auditFor(driver.getId(), "DRIVER_SUSPENDED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getActorId()).isEqualTo(admin.getId());
        assertThat(rows.get(0).getMetadata()).containsEntry("reason", "Complaint verified by support");
    }

    @Test
    void suspendWithoutReasonIs400AndChangesNothing() throws Exception {
        Driver driver = approvedDriver();
        User admin = staff(UserRole.ADMIN);

        suspend(driver.getId(), fresh(admin), "{}").andExpect(status().isBadRequest());
        suspend(driver.getId(), fresh(admin), "{\"reason\":\"   \"}").andExpect(status().isBadRequest());

        flushAndClear();
        assertThat(drivers.findById(driver.getId()).orElseThrow().getAvailabilityStatus())
                .isEqualTo(DriverAvailability.ONLINE);
        assertThat(auditFor(driver.getId(), "DRIVER_SUSPENDED")).isEmpty();
    }

    @Test
    void suspendWithoutStepUpIs403StepUpRequired() throws Exception {
        Driver driver = approvedDriver();
        User admin = staff(UserRole.ADMIN);

        suspend(driver.getId(), stale(admin), "{\"reason\":\"x\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));

        flushAndClear();
        assertThat(drivers.findById(driver.getId()).orElseThrow().getVerificationStatus())
                .isEqualTo(DriverVerificationStatus.APPROVED);
        assertThat(auditFor(driver.getId(), "DRIVER_SUSPENDED")).isEmpty();
    }

    @Test
    void suspendIsAdminAndAboveOnly() throws Exception {
        Driver driver = approvedDriver();
        suspend(driver.getId(), fresh(staff(UserRole.SUPPORT)), "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        suspend(driver.getId(), fresh(newUser(UserRole.CUSTOMER)), "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        suspend(driver.getId(), fresh(newUser(UserRole.DRIVER)), "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        suspend(driver.getId(), fresh(staff(UserRole.SUPER_ADMIN)), "{\"reason\":\"ok\"}").andExpect(status().isOk());
    }

    @Test
    void suspendingUnknownDriverIs404() throws Exception {
        suspend(UUID.randomUUID(), fresh(staff(UserRole.ADMIN)), "{\"reason\":\"x\"}").andExpect(status().isNotFound());
    }

    @Test
    void onlyAnApprovedDriverCanBeSuspended() throws Exception {
        Driver pending = newDriver(newCity());
        pending.setVerificationStatus(DriverVerificationStatus.PENDING_REVIEW);
        drivers.saveAndFlush(pending);

        suspend(pending.getId(), fresh(staff(UserRole.ADMIN)), "{\"reason\":\"x\"}").andExpect(status().isBadRequest());
    }

    // ---- activate ------------------------------------------------------------------------------

    @Test
    void adminReactivatesASuspendedDriverToApprovedOfflineWithAudit() throws Exception {
        Driver driver = approvedDriver();
        User admin = staff(UserRole.ADMIN);
        suspend(driver.getId(), fresh(admin), "{\"reason\":\"first\"}").andExpect(status().isOk());

        activate(driver.getId(), fresh(admin), "{\"reason\":\"Cleared after review\"}").andExpect(status().isOk());

        flushAndClear();
        Driver after = drivers.findById(driver.getId()).orElseThrow();
        assertThat(after.getVerificationStatus()).isEqualTo(DriverVerificationStatus.APPROVED);
        assertThat(after.getAvailabilityStatus()).isEqualTo(DriverAvailability.OFFLINE);
        assertThat(auditFor(driver.getId(), "DRIVER_ACTIVATED")).hasSize(1);
    }

    @Test
    void activateNeedsReasonStepUpAndAdminRole() throws Exception {
        Driver driver = approvedDriver();
        User admin = staff(UserRole.ADMIN);
        suspend(driver.getId(), fresh(admin), "{\"reason\":\"first\"}").andExpect(status().isOk());

        activate(driver.getId(), fresh(admin), "{}").andExpect(status().isBadRequest());
        activate(driver.getId(), stale(admin), "{\"reason\":\"x\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));
        activate(driver.getId(), fresh(staff(UserRole.SUPPORT)), "{\"reason\":\"x\"}").andExpect(status().isForbidden());

        flushAndClear();
        assertThat(drivers.findById(driver.getId()).orElseThrow().getVerificationStatus())
                .isEqualTo(DriverVerificationStatus.SUSPENDED);
        assertThat(auditFor(driver.getId(), "DRIVER_ACTIVATED")).isEmpty();
    }

    @Test
    void onlyASuspendedDriverCanBeActivated() throws Exception {
        Driver driver = approvedDriver();
        activate(driver.getId(), fresh(staff(UserRole.ADMIN)), "{\"reason\":\"x\"}").andExpect(status().isBadRequest());
    }
}
