package com.ladygo.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.auth.service.RefreshTokenService;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.customers.domain.Customer;
import com.ladygo.locations.domain.City;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
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

/** Admin customers screen API: read for SUPPORT and above, suspend/activate for ADMIN and above with a
 *  mandatory reason, a fresh step-up and an audit row. */
@AutoConfigureMockMvc
class AdminCustomerControllerIntegrationTest extends PersistenceTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private RefreshTokenService refreshTokens;

    private String token(User user, Instant stepUpAt) {
        return jwtService.issueAccessToken(user.getId(), user.getRole(), Duration.ofMinutes(10), stepUpAt);
    }

    private String plain(UserRole role) {
        return token(newUser(role), null);
    }

    /** An ADMIN-or-above account with an authenticator, which the step-up guard requires. */
    private User enrolled(UserRole role) {
        User user = newUser(role);
        user.setMfaEnabled(true);
        return users.saveAndFlush(user);
    }

    private ResultActions act(String action, UUID customerId, String bearer, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/customers/" + customerId + "/" + action)
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private UserStatus statusOf(Customer customer) {
        flushAndClear();
        return users.findById(customer.getUserId()).orElseThrow().getStatus();
    }

    private List<AuditLog> auditFor(Customer customer, String action) {
        return auditLogs.findAll().stream()
                .filter(a -> action.equals(a.getAction()) && customer.getUserId().toString().equals(a.getTargetId()))
                .toList();
    }

    // ---- reads ----

    @Test
    void supportCanListAndPhoneIsMasked() throws Exception {
        Customer customer = newCustomer();
        String phone = users.findById(customer.getUserId()).orElseThrow().getPhone();

        mockMvc.perform(get("/api/v1/admin/customers").param("q", phone.substring(phone.length() - 6))
                        .header("Authorization", "Bearer " + plain(UserRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(customer.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].phoneMasked").value(
                        "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 4)))
                .andExpect(jsonPath("$.data.content[0].phone").doesNotExist());
    }

    @Test
    void listFiltersByStatusAndSearchesByNameAndId() throws Exception {
        Customer active = newCustomer();
        Customer suspended = newCustomer();
        User suspendedUser = users.findById(suspended.getUserId()).orElseThrow();
        suspendedUser.setStatus(UserStatus.SUSPENDED);
        suspendedUser.setName("Zainab Zzyzx");
        users.saveAndFlush(suspendedUser);
        String bearer = plain(UserRole.ADMIN);

        mockMvc.perform(get("/api/v1/admin/customers").param("status", "SUSPENDED").param("q", "zzyzx")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(suspended.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].status").value("SUSPENDED"));

        mockMvc.perform(get("/api/v1/admin/customers").param("q", active.getId().toString())
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(active.getId().toString()));
    }

    @Test
    void detailShowsRideHistoryWithFareAndCounts() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        com.ladygo.bookings.domain.Booking done = newBooking(customer, city, BookingStatus.COMPLETED, null);
        done.setFinalFare(18500L);
        bookings.saveAndFlush(done);
        newBooking(customer, city, BookingStatus.CANCELLED, null);

        mockMvc.perform(get("/api/v1/admin/customers/" + customer.getId())
                        .header("Authorization", "Bearer " + plain(UserRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.totalRides").value(2))
                .andExpect(jsonPath("$.data.completedRides").value(1))
                .andExpect(jsonPath("$.data.cancelledRides").value(1))
                .andExpect(jsonPath("$.data.rides.length()").value(2))
                .andExpect(jsonPath("$.data.rides[?(@.bookingId=='" + done.getId() + "')].finalFare").value(18500));
    }

    @Test
    void unknownCustomerIs404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/customers/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + plain(UserRole.ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void driversAndCustomersCannotReadAndAnonymousIs401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/customers").header("Authorization", "Bearer " + plain(UserRole.DRIVER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/customers").header("Authorization", "Bearer " + plain(UserRole.CUSTOMER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/customers")).andExpect(status().isUnauthorized());
    }

    // ---- writes: role matrix ----

    @Test
    void supportCannotSuspend() throws Exception {
        Customer customer = newCustomer();
        User support = enrolled(UserRole.SUPPORT);

        act("suspend", customer.getId(), token(support, Instant.now()), "{\"reason\":\"abuse\"}")
                .andExpect(status().isForbidden());

        assertThat(statusOf(customer)).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void customerAndDriverCannotSuspend() throws Exception {
        Customer customer = newCustomer();
        for (UserRole role : List.of(UserRole.CUSTOMER, UserRole.DRIVER)) {
            act("suspend", customer.getId(), token(enrolled(role), Instant.now()), "{\"reason\":\"abuse\"}")
                    .andExpect(status().isForbidden());
        }
        assertThat(statusOf(customer)).isEqualTo(UserStatus.ACTIVE);
    }

    // ---- writes: reason + step-up ----

    @Test
    void missingOrBlankReasonIs400() throws Exception {
        Customer customer = newCustomer();
        String bearer = token(enrolled(UserRole.ADMIN), Instant.now());

        act("suspend", customer.getId(), bearer, "{}").andExpect(status().isBadRequest());
        act("suspend", customer.getId(), bearer, "{\"reason\":\"   \"}").andExpect(status().isBadRequest());

        assertThat(statusOf(customer)).isEqualTo(UserStatus.ACTIVE);
        assertThat(auditFor(customer, "CUSTOMER_SUSPENDED")).isEmpty();
    }

    @Test
    void withoutStepUpIs403StepUpRequired() throws Exception {
        Customer customer = newCustomer();

        act("suspend", customer.getId(), token(enrolled(UserRole.ADMIN), null), "{\"reason\":\"abuse\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));
        act("activate", customer.getId(), token(enrolled(UserRole.ADMIN), null), "{\"reason\":\"appeal\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));

        assertThat(statusOf(customer)).isEqualTo(UserStatus.ACTIVE);
    }

    // ---- writes: behaviour ----

    @Test
    void adminSuspendsEndsSessionsAndWritesAudit() throws Exception {
        Customer customer = newCustomer();
        String refresh = refreshTokens.issue(customer.getUserId(), "device-1");
        User admin = enrolled(UserRole.ADMIN);

        act("suspend", customer.getId(), token(admin, Instant.now()), "{\"reason\":\"  repeated abuse  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));

        assertThat(statusOf(customer)).isEqualTo(UserStatus.SUSPENDED);
        assertThat(users.findById(customer.getUserId()).orElseThrow().getStatusChangedAt()).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, customer.getUserId())).isZero();
        assertThatRefreshFails(refresh);

        List<AuditLog> rows = auditFor(customer, "CUSTOMER_SUSPENDED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getActorId()).isEqualTo(admin.getId());
        assertThat(rows.get(0).getActorRole()).isEqualTo("ADMIN");
        assertThat(rows.get(0).getTargetType()).isEqualTo("users");
        assertThat(rows.get(0).getMetadata()).containsEntry("reason", "repeated abuse");
    }

    private void assertThatRefreshFails(String raw) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> refreshTokens.rotate(raw))
                .isInstanceOf(com.ladygo.common.exception.ApiException.class);
    }

    @Test
    void superAdminActivatesASuspendedCustomerWithAudit() throws Exception {
        Customer customer = newCustomer();
        User user = users.findById(customer.getUserId()).orElseThrow();
        user.setStatus(UserStatus.SUSPENDED);
        users.saveAndFlush(user);
        User admin = enrolled(UserRole.SUPER_ADMIN);

        act("activate", customer.getId(), token(admin, Instant.now()), "{\"reason\":\"appeal accepted\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        assertThat(statusOf(customer)).isEqualTo(UserStatus.ACTIVE);
        List<AuditLog> rows = auditFor(customer, "CUSTOMER_ACTIVATED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getMetadata()).containsEntry("reason", "appeal accepted");
    }

    @Test
    void suspendingAnAlreadySuspendedOrDeletedCustomerIs400AndWritesNoAudit() throws Exception {
        Customer customer = newCustomer();
        User user = users.findById(customer.getUserId()).orElseThrow();
        user.setStatus(UserStatus.DELETED);
        users.saveAndFlush(user);
        String bearer = token(enrolled(UserRole.ADMIN), Instant.now());

        act("suspend", customer.getId(), bearer, "{\"reason\":\"x\"}").andExpect(status().isBadRequest());
        act("activate", customer.getId(), bearer, "{\"reason\":\"x\"}").andExpect(status().isBadRequest());

        assertThat(statusOf(customer)).isEqualTo(UserStatus.DELETED);
        assertThat(auditFor(customer, "CUSTOMER_SUSPENDED")).isEmpty();
        assertThat(auditFor(customer, "CUSTOMER_ACTIVATED")).isEmpty();
    }

    @Test
    void unknownCustomerSuspendIs404() throws Exception {
        act("suspend", UUID.randomUUID(), token(enrolled(UserRole.ADMIN), Instant.now()), "{\"reason\":\"x\"}")
                .andExpect(status().isNotFound());
    }
}
