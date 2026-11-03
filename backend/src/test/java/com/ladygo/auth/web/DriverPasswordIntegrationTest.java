package com.ladygo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.auth.repository.RefreshTokenRepository;
import com.ladygo.auth.service.AccessTokenIssuer;
import com.ladygo.auth.service.RefreshTokenService;
import com.ladygo.notifications.email.EmailSender;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Driver password sign-in, first password, change and reset. Deliberately NOT wrapped in a test transaction: the
 * attempt counters only exist if they are committed even though the request is rejected, and a surrounding
 * transaction would hide exactly that. Rows are removed in {@link #cleanUp}.
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DriverPasswordIntegrationTest extends PersistenceTestSupport {

    private static final Pattern LINK_TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]{40,})");
    private static final String PASSWORD = "river-stone-lantern-84";
    private static final String NEW_PASSWORD = "another-quiet-meadow-31";
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AccessTokenIssuer accessTokenIssuer;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private AuditLogRepository auditLogs;
    @MockitoBean private EmailSender emailSender;

    private final List<UUID> created = new ArrayList<>();
    private final List<String> ips = new ArrayList<>();

    @BeforeEach
    void enabled() {
        setConfig("auth.driver_password.enabled", "true");
    }

    @AfterEach
    void cleanUp() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM admin_password_resets WHERE user_id = ?", id);
            jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", id);
            jdbc.update("DELETE FROM audit_logs WHERE actor_id = ? OR target_id = ?", id, id.toString());
            jdbc.update("DELETE FROM admin_login_throttle WHERE email = ?", "driver:" + id);
            jdbc.update("DELETE FROM auth_rate_limits WHERE bucket_key = ?", "driver_pwchange:" + id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        for (String ip : ips) {
            jdbc.update("DELETE FROM auth_rate_limits WHERE bucket_key LIKE ?", "%" + ip);
        }
        jdbc.update("DELETE FROM admin_login_throttle WHERE email LIKE 'driver:%'");
        jdbc.update("DELETE FROM auth_rate_limits WHERE bucket_key LIKE 'driver_pwreset_req_id:%'");
        jdbc.update("UPDATE configuration SET value = '20' WHERE key = 'driver.password.login.max.per.ip'");
        setConfig("auth.driver_password.enabled", "true");
        created.clear();
        ips.clear();
    }

    private void setConfig(String key, String value) {
        jdbc.update("UPDATE configuration SET value = ? WHERE key = ?", value, key);
    }

    private String freshIp() {
        int n = SEQ.incrementAndGet();
        String ip = "203.0." + (n / 250) + "." + (n % 250 + 1);
        ips.add(ip);
        return ip;
    }

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private User driver(boolean withPassword) {
        User user = newUser(UserRole.DRIVER);
        user.setEmail("captain" + SEQ.incrementAndGet() + "@example.test");
        user.setLastLoginAt(Instant.now());
        if (withPassword) {
            user.setPasswordHash(passwordEncoder.encode(PASSWORD));
            user.setPasswordSetAt(Instant.now());
        }
        User saved = users.saveAndFlush(user);
        created.add(saved.getId());
        return saved;
    }

    private String bearer(User user) {
        return "Bearer " + accessTokenIssuer.issue(user.getId(), user.getRole());
    }

    private ResultActions login(String identifier, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/driver/password/login")
                .with(remoteAddr(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}"));
    }

    /** The error code and message of a 401, without the per-request id and timestamp. */
    private String errorOf(ResultActions result) throws Exception {
        String body = result.andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        return node.path("error").path("code").asText() + "|" + node.path("error").path("message").asText();
    }

    private ResultActions setPassword(User user, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/driver/password/set")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"" + password + "\"}"));
    }

    private void forgot(String identifier) throws Exception {
        mockMvc.perform(post("/api/v1/auth/driver/password/forgot")
                        .with(remoteAddr(freshIp()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + identifier + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("If that account exists, we have emailed a link to choose a new password."));
    }

    private String requestToken(User user) throws Exception {
        forgot(user.getEmail());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender, timeout(3000)).send(eq(user.getEmail()), anyString(), body.capture());
        Matcher matcher = LINK_TOKEN.matcher(body.getValue());
        assertThat(matcher.find()).as("reset link with a fragment token").isTrue();
        assertThat(body.getValue()).contains("/captain/reset-password#token=");
        return matcher.group(1);
    }

    private ResultActions reset(String token, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/driver/password/reset")
                .with(remoteAddr(freshIp()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}"));
    }

    // ---- sign in ----

    @Test
    void signsInWithPhoneOrEmailAndNeverEchoesThePassword(CapturedOutput output) throws Exception {
        User user = driver(true);
        String body = login(user.getPhone(), PASSWORD, freshIp()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        login(user.getEmail().toUpperCase(), PASSWORD, freshIp()).andExpect(status().isOk());
        // a plain 10-digit number is accepted like on the website (+91 added)
        String tenDigits = user.getPhone().substring(3);
        login(tenDigits, PASSWORD, freshIp()).andExpect(status().isOk());

        assertThat(body).doesNotContain(PASSWORD).doesNotContain("passwordHash");
        assertThat(output.getAll()).doesNotContain(PASSWORD);
    }

    @Test
    void everyKindOfFailureLooksTheSame() throws Exception {
        User withPassword = driver(true);
        User noPassword = driver(false);
        String wrong = errorOf(login(withPassword.getPhone(), "not-the-password-1", freshIp()));
        String unknown = errorOf(login("+919800000000", "not-the-password-1", freshIp()));
        String none = errorOf(login(noPassword.getPhone(), "not-the-password-1", freshIp()));
        assertThat(unknown).isEqualTo(wrong);
        assertThat(none).isEqualTo(wrong);
    }

    @Test
    void aRejectedAttemptIsCountedAndTheAccountWaitsEvenWithTheRightPassword() throws Exception {
        User user = driver(true);
        login(user.getPhone(), "wrong-password-123", freshIp()).andExpect(status().isUnauthorized());

        Integer failures = jdbc.queryForObject(
                "SELECT failure_count FROM admin_login_throttle WHERE email = ?", Integer.class, "driver:" + user.getId());
        assertThat(failures).isEqualTo(1);
        // Same account via its email, from another IP: still waiting.
        login(user.getEmail(), PASSWORD, freshIp()).andExpect(status().isTooManyRequests());

        jdbc.update("UPDATE admin_login_throttle SET locked_until = now() - interval '1 second' WHERE email = ?", "driver:" + user.getId());
        login(user.getEmail(), PASSWORD, freshIp()).andExpect(status().isOk());
        Integer left = jdbc.queryForObject(
                "SELECT count(*) FROM admin_login_throttle WHERE email = ?", Integer.class, "driver:" + user.getId());
        assertThat(left).isZero();
    }

    @Test
    void unknownAccountsAreCountedToo() throws Exception {
        login("+919811111111", "wrong-password-123", freshIp()).andExpect(status().isUnauthorized());
        login("+919811111111", "wrong-password-123", freshIp()).andExpect(status().isTooManyRequests());
    }

    @Test
    void theIpAttemptCapStopsGuessingAcrossAccounts() throws Exception {
        setConfig("driver.password.login.max.per.ip", "2");
        String ip = freshIp();
        login("+919822222221", "wrong-password-123", ip).andExpect(status().isUnauthorized());
        login("+919822222222", "wrong-password-123", ip).andExpect(status().isUnauthorized());
        login("+919822222223", "wrong-password-123", ip).andExpect(status().isTooManyRequests());
    }

    @Test
    void aCustomerOrAnAdminCannotUseTheDriverEndpoint() throws Exception {
        for (UserRole role : new UserRole[] {UserRole.CUSTOMER, UserRole.ADMIN}) {
            User other = newUser(role);
            created.add(other.getId());
            other.setEmail("other" + SEQ.incrementAndGet() + "@example.test");
            if (role != UserRole.CUSTOMER) { // the database refuses a password on a customer
                other.setPasswordHash(passwordEncoder.encode(PASSWORD));
            }
            users.saveAndFlush(other);
            login(other.getPhone(), PASSWORD, freshIp()).andExpect(status().isUnauthorized());
            login(other.getEmail(), PASSWORD, freshIp()).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void aSuspendedCaptainWithTheRightPasswordGetsForbidden() throws Exception {
        User user = driver(true);
        user.setStatus(UserStatus.SUSPENDED);
        users.saveAndFlush(user);
        login(user.getPhone(), PASSWORD, freshIp()).andExpect(status().isForbidden());
    }

    @Test
    void theKillSwitchTurnsPasswordSignInOffWithoutTouchingOtp() throws Exception {
        User user = driver(true);
        setConfig("auth.driver_password.enabled", "false");
        login(user.getPhone(), PASSWORD, freshIp()).andExpect(status().isNotFound());
        setPassword(driver(false), NEW_PASSWORD).andExpect(status().isNotFound());
    }

    // ---- first password ----

    @Test
    void aFreshlySignedInDriverSetsAFirstPasswordThenSignsInWithIt() throws Exception {
        User user = driver(false);
        mockMvc.perform(get("/api/v1/auth/driver/password/status").header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.passwordSet").value(false));

        setPassword(user, NEW_PASSWORD).andExpect(status().isOk());

        User saved = users.findById(user.getId()).orElseThrow();
        assertThat(saved.getPasswordHash()).isNotEqualTo(NEW_PASSWORD);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, saved.getPasswordHash())).isTrue();
        assertThat(saved.getPasswordSetAt()).isNotNull();
        assertThat(auditLogs.findAll()).anySatisfy(a -> {
            assertThat(a.getAction()).isEqualTo("DRIVER_PASSWORD_SET");
            assertThat(a.getTargetId()).isEqualTo(user.getId().toString());
        });
        login(user.getPhone(), NEW_PASSWORD, freshIp()).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/driver/password/status").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.data.passwordSet").value(true));
    }

    @Test
    void aFirstPasswordNeedsARecentSignIn() throws Exception {
        User user = driver(false);
        user.setLastLoginAt(Instant.now().minus(1, ChronoUnit.HOURS));
        users.saveAndFlush(user);
        setPassword(user, NEW_PASSWORD).andExpect(status().isForbidden());
        assertThat(users.findById(user.getId()).orElseThrow().getPasswordHash()).isNull();
    }

    @Test
    void weakPasswordsAreRefusedWithAPlainReason() throws Exception {
        User user = driver(false);
        setPassword(user, "short1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("Use at least 10 characters."));
        setPassword(user, "Password123").andExpect(status().isBadRequest());
        setPassword(user, "1111111111111").andExpect(status().isBadRequest());
        setPassword(user, "x".repeat(80)).andExpect(status().isBadRequest());
        String phoneDigits = user.getPhone().substring(3);
        setPassword(user, phoneDigits).andExpect(status().isBadRequest());
        setPassword(user, user.getEmail()).andExpect(status().isBadRequest());
        assertThat(users.findById(user.getId()).orElseThrow().getPasswordHash()).isNull();
        // no composition rules: a long, lower-case phrase with spaces is fine
        setPassword(user, "my little pink scooter").andExpect(status().isOk());
    }

    @Test
    void aSecondFirstPasswordAndNonDriversAreRefused() throws Exception {
        User user = driver(true);
        setPassword(user, NEW_PASSWORD).andExpect(status().isForbidden());
        User customer = newUser(UserRole.CUSTOMER);
        created.add(customer.getId());
        setPassword(customer, NEW_PASSWORD).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/auth/driver/password/set")
                .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- change ----

    @Test
    void changingNeedsTheCurrentPasswordCountsWrongGuessesAndEndsEverySession() throws Exception {
        User user = driver(true);
        refreshTokenService.issue(user.getId(), "old-device");
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/driver/password/change").header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"wrong-guess-" + i + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/driver/password/change").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isTooManyRequests());

        jdbc.update("DELETE FROM auth_rate_limits WHERE bucket_key = ?", "driver_pwchange:" + user.getId());
        mockMvc.perform(post("/api/v1/auth/driver/password/change").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        assertThat(refreshTokens.findAll()).filteredOn(t -> t.getUserId().equals(user.getId()))
                .filteredOn(t -> "old-device".equals(t.getDeviceId()))
                .allSatisfy(t -> assertThat(t.getRevokedAt()).isNotNull());
        login(user.getPhone(), NEW_PASSWORD, freshIp()).andExpect(status().isOk());
    }

    // ---- forgot / reset ----

    @Test
    void forgotGivesTheSameReplyForUnknownCustomerAndSuspendedAccountsAndEmailsNobody() throws Exception {
        User customer = newUser(UserRole.CUSTOMER);
        created.add(customer.getId());
        customer.setEmail("rider" + SEQ.incrementAndGet() + "@example.test");
        users.saveAndFlush(customer);
        User suspended = driver(true);
        suspended.setStatus(UserStatus.SUSPENDED);
        users.saveAndFlush(suspended);

        forgot("nobody@example.test");
        forgot(customer.getEmail());
        forgot(suspended.getEmail());
        User control = driver(true);
        forgot(control.getEmail()); // a single FIFO worker: once this arrives, the others were decided
        verify(emailSender, timeout(5000)).send(eq(control.getEmail()), anyString(), anyString());

        verify(emailSender, never()).send(eq("nobody@example.test"), anyString(), anyString());
        verify(emailSender, never()).send(eq(customer.getEmail()), anyString(), anyString());
        verify(emailSender, never()).send(eq(suspended.getEmail()), anyString(), anyString());
    }

    @Test
    void aResetLinkSetsThePasswordOnceRevokesSessionsAndClearsTheWait() throws Exception {
        User user = driver(true);
        refreshTokenService.issue(user.getId(), "phone-1");
        login(user.getPhone(), "wrong-password-123", freshIp()).andExpect(status().isUnauthorized());
        String token = requestToken(user);
        String stored = jdbc.queryForObject("SELECT token_hash FROM admin_password_resets WHERE user_id = ?", String.class, user.getId());
        assertThat(stored).hasSize(64).isNotEqualTo(token);

        reset(token, NEW_PASSWORD).andExpect(status().isOk());

        login(user.getPhone(), NEW_PASSWORD, freshIp()).andExpect(status().isOk());
        login(user.getPhone(), PASSWORD, freshIp()).andExpect(status().isUnauthorized());
        assertThat(refreshTokens.findAll()).filteredOn(t -> "phone-1".equals(t.getDeviceId()))
                .allSatisfy(t -> assertThat(t.getRevokedAt()).isNotNull());
        assertThat(auditLogs.findAll()).anySatisfy(a -> {
            assertThat(a.getAction()).isEqualTo("DRIVER_PASSWORD_RESET");
            assertThat(a.getTargetId()).isEqualTo(user.getId().toString());
        });
        reset(token, "yet-another-pass-77").andExpect(status().isUnauthorized());
    }

    @Test
    void aWeakNewPasswordDoesNotUseUpTheLink() throws Exception {
        User user = driver(true);
        String token = requestToken(user);
        reset(token, "password123").andExpect(status().isBadRequest());
        reset(token, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void anExpiredOrGarbageLinkIsRefused() throws Exception {
        User user = driver(true);
        String token = requestToken(user);
        jdbc.update("UPDATE admin_password_resets SET expires_at = now() - interval '1 minute' WHERE user_id = ?", user.getId());
        reset(token, NEW_PASSWORD).andExpect(status().isUnauthorized());
        reset("x".repeat(43), NEW_PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminLinkCannotResetADriverPasswordOrTheOtherWayRound() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        created.add(admin.getId());
        String adminToken = "A".repeat(43);
        jdbc.update("INSERT INTO admin_password_resets (user_id, token_hash, expires_at) VALUES (?, encode(sha256(?::bytea), 'hex'), now() + interval '10 minutes')",
                admin.getId(), adminToken);
        reset(adminToken, NEW_PASSWORD).andExpect(status().isUnauthorized());

        User user = driver(true);
        String token = requestToken(user);
        mockMvc.perform(post("/api/v1/auth/admin/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"Brand-new-passphrase-42\"}"))
                .andExpect(status().isUnauthorized());
    }
}
