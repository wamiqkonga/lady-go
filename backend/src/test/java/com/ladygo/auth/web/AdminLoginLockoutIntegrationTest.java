package com.ladygo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.security.crypto.MfaSecretCipher;
import com.ladygo.security.crypto.TotpService;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** docs/security/admin-stepup-lockout-recovery-design.md section 3. Deliberately NOT wrapped in a test transaction:
 *  a failed attempt must still be counted once the request has been rejected, and a surrounding transaction hides
 *  exactly that. Rows are removed in {@link #cleanUp}. */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminLoginLockoutIntegrationTest extends PersistenceTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static final String PASSWORD = "InitialPass123!";
    private static final String FLAG = "admin.login.progressive.enabled";

    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TotpService totpService;
    @Autowired private MfaSecretCipher mfaSecretCipher;
    @Autowired private JwtService jwtService;
    @Autowired private AuditLogRepository auditLogs;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<String> emails = new ArrayList<>();
    private final List<String> ips = new ArrayList<>();
    private String secret;

    @AfterEach
    void cleanUp() {
        for (UUID id : createdUsers) {
            jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", id);
            jdbc.update("DELETE FROM audit_logs WHERE actor_id = ? OR target_id = ?", id, id.toString());
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        for (String email : emails) {
            jdbc.update("DELETE FROM admin_login_throttle WHERE email = ?", email);
            jdbc.update("DELETE FROM auth_rate_limits WHERE bucket_key = ?", "admin_login_email:" + email);
        }
        for (String ip : ips) {
            jdbc.update("DELETE FROM auth_rate_limits WHERE bucket_key = ?", "admin_login_ip:" + ip);
        }
        jdbc.update("DELETE FROM auth_rate_limits WHERE bucket_key LIKE 'admin_stepup:%'");
        jdbc.update("UPDATE configuration SET value = 'false' WHERE key = ?", FLAG);
        createdUsers.clear();
        emails.clear();
        ips.clear();
    }

    private void progressive(boolean on) {
        jdbc.update("UPDATE configuration SET value = ? WHERE key = ?", Boolean.toString(on), FLAG);
    }

    private String freshIp() {
        int n = SEQ.incrementAndGet();
        String ip = "198.51." + (n / 250) + "." + (n % 250 + 1);
        ips.add(ip);
        return ip;
    }

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private User admin(UserRole role) {
        String email = "lockout" + SEQ.incrementAndGet() + "@ladygo.test";
        User user = new User();
        user.setPhone("+9196" + String.format("%08d", SEQ.incrementAndGet()));
        user.setEmail(email);
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setMustChangePassword(false);
        user.setMfaEnabled(true);
        secret = totpService.generateSecret();
        user.setMfaSecretEnc(mfaSecretCipher.encrypt(secret));
        User saved = users.saveAndFlush(user);
        createdUsers.add(saved.getId());
        emails.add(email);
        return saved;
    }

    private String code() {
        return totpService.generateCode(secret, totpService.currentStep(Instant.now()));
    }

    private ResultActions login(String email, String password, String totp, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/admin/login")
                .header("Origin", "http://localhost:5174")
                .with(remoteAddr(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"totpCode\":\"" + totp + "\"}"));
    }

    private void failOnce(String email, String ip) throws Exception {
        login(email, "wrong-password-1", "000000", ip).andExpect(status().isUnauthorized());
    }

    private void waitOutTheDelay(String email) {
        jdbc.update("UPDATE admin_login_throttle SET locked_until = now() - interval '1 second' WHERE email = ?", email);
    }

    // --- the counting bug: a rejected attempt must still be remembered -----------------------------------------

    @Test
    void wrongPasswordsAreCountedEvenThoughEachAttemptIsRejected() throws Exception {
        User user = admin(UserRole.ADMIN);
        String ip = freshIp();

        for (int i = 0; i < 5; i++) {
            failOnce(user.getEmail(), ip);
        }

        // The sixth attempt is refused even with the right password and code.
        login(user.getEmail(), PASSWORD, code(), ip).andExpect(status().isTooManyRequests());
    }

    @Test
    void wrongStepUpCodesAreCountedEvenThoughEachAttemptIsRejected() throws Exception {
        User user = admin(UserRole.SUPER_ADMIN);
        String bearer = jwtService.issueAccessToken(user.getId(), user.getRole(), Duration.ofMinutes(10), null);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/admin/step-up")
                            .header("Authorization", "Bearer " + bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"000000\"}"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/admin/step-up")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code() + "\"}"))
                .andExpect(status().isTooManyRequests());
    }

    // --- progressive delay (behind admin.login.progressive.enabled) --------------------------------------------

    @Test
    void aFailureMakesTheNextAttemptWaitAndTheRightPasswordDuringTheWaitIsRefused() throws Exception {
        progressive(true);
        User user = admin(UserRole.ADMIN);
        String ip = freshIp();

        failOnce(user.getEmail(), ip);

        login(user.getEmail(), PASSWORD, code(), ip).andExpect(status().isTooManyRequests());
        waitOutTheDelay(user.getEmail());
        login(user.getEmail(), PASSWORD, code(), ip).andExpect(status().isOk());
    }

    @Test
    void theWaitDoublesWithEachFailureAndIsCappedAtFifteenMinutes() throws Exception {
        progressive(true);
        User user = admin(UserRole.ADMIN);
        String ip = freshIp();

        failOnce(user.getEmail(), ip);
        assertThat(waitSeconds(user.getEmail())).isBetween(0.5, 1.6);
        waitOutTheDelay(user.getEmail());
        failOnce(user.getEmail(), ip);
        assertThat(waitSeconds(user.getEmail())).isBetween(1.5, 2.6);
        waitOutTheDelay(user.getEmail());
        failOnce(user.getEmail(), ip);
        assertThat(waitSeconds(user.getEmail())).isBetween(3.5, 4.6);

        jdbc.update("UPDATE admin_login_throttle SET failure_count = 40, locked_until = now() - interval '1 second' WHERE email = ?",
                user.getEmail());
        failOnce(user.getEmail(), ip);
        assertThat(waitSeconds(user.getEmail())).isBetween(890.0, 901.0);
    }

    private double waitSeconds(String email) {
        return jdbc.queryForObject(
                "SELECT extract(epoch FROM (locked_until - last_failure_at)) FROM admin_login_throttle WHERE email = ?",
                Double.class, email);
    }

    @Test
    void aSuccessfulLoginClearsTheFailureHistory() throws Exception {
        progressive(true);
        User user = admin(UserRole.ADMIN);
        String ip = freshIp();
        failOnce(user.getEmail(), ip);
        waitOutTheDelay(user.getEmail());

        login(user.getEmail(), PASSWORD, code(), ip).andExpect(status().isOk());

        Integer rows = jdbc.queryForObject("SELECT count(*) FROM admin_login_throttle WHERE email = ?", Integer.class, user.getEmail());
        assertThat(rows).isZero();
    }

    @Test
    void anUnknownEmailGetsTheSameWaitSoTheReplyDoesNotRevealWhoIsAnAdmin() throws Exception {
        progressive(true);
        String email = "ghost" + SEQ.incrementAndGet() + "@ladygo.test";
        emails.add(email);
        String ip = freshIp();

        failOnce(email, ip);

        login(email, PASSWORD, "123456", ip).andExpect(status().isTooManyRequests());
    }

    @Test
    void theEmailIsMatchedIgnoringCaseAndSpaces() throws Exception {
        progressive(true);
        User user = admin(UserRole.ADMIN);
        String ip = freshIp();

        failOnce(user.getEmail().toUpperCase(), ip);

        login(user.getEmail(), PASSWORD, code(), ip).andExpect(status().isTooManyRequests());
    }

    @Test
    void manyFailuresInAShortTimeWriteAnAlertLine(CapturedOutput output) throws Exception {
        progressive(true);
        User user = admin(UserRole.ADMIN);
        String ip = freshIp();

        for (int i = 0; i < 10; i++) {
            failOnce(user.getEmail(), ip);
            waitOutTheDelay(user.getEmail());
        }

        assertThat(output.getAll()).contains("operation=admin_login_failures_high").contains("failures=10");
    }

    @Test
    void withTheSwitchOffTheOldFixedWindowStillApplies() throws Exception {
        progressive(false);
        User user = admin(UserRole.ADMIN);
        String ip = freshIp();

        failOnce(user.getEmail(), ip);

        // No delay after one failure in the old mode.
        login(user.getEmail(), PASSWORD, code(), ip).andExpect(status().isOk());
    }

    // --- SUPER_ADMIN unlock ------------------------------------------------------------------------------------

    private ResultActions unlock(User target, String bearer, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/users/" + target.getId() + "/unlock")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void aSuperAdminCanUnlockALockedAdminWithAReasonAndItIsAudited() throws Exception {
        progressive(true);
        User target = admin(UserRole.ADMIN);
        String targetSecret = secret;
        String ip = freshIp();
        failOnce(target.getEmail(), ip);
        User boss = admin(UserRole.SUPER_ADMIN);
        String bearer = jwtService.issueAccessToken(boss.getId(), boss.getRole(), Duration.ofMinutes(10), Instant.now());

        unlock(target, bearer, "{\"reason\":\"Locked out after a typo, verified by phone\"}").andExpect(status().isOk());

        secret = targetSecret;
        login(target.getEmail(), PASSWORD, code(), ip).andExpect(status().isOk());
        assertThat(auditLogs.findAll()).anySatisfy(log -> {
            assertThat(log.getAction()).isEqualTo("ADMIN_LOGIN_UNLOCK");
            assertThat(log.getTargetId()).isEqualTo(target.getId().toString());
            assertThat(log.getActorId()).isEqualTo(boss.getId());
        });
    }

    @Test
    void unlockAlsoClearsTheOldFixedWindowCounter() throws Exception {
        User target = admin(UserRole.ADMIN);
        String targetSecret = secret;
        String ip = freshIp();
        for (int i = 0; i < 5; i++) {
            failOnce(target.getEmail(), ip);
        }
        User boss = admin(UserRole.SUPER_ADMIN);
        String bearer = jwtService.issueAccessToken(boss.getId(), boss.getRole(), Duration.ofMinutes(10), Instant.now());

        unlock(target, bearer, "{\"reason\":\"Locked out, verified by phone\"}").andExpect(status().isOk());

        secret = targetSecret;
        login(target.getEmail(), PASSWORD, code(), freshIp()).andExpect(status().isOk());
    }

    @Test
    void unlockNeedsAFreshStepUp() throws Exception {
        User target = admin(UserRole.ADMIN);
        User boss = admin(UserRole.SUPER_ADMIN);
        String bearer = jwtService.issueAccessToken(boss.getId(), boss.getRole(), Duration.ofMinutes(10), null);

        unlock(target, bearer, "{\"reason\":\"Locked out, verified by phone\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));
    }

    @Test
    void unlockNeedsAReason() throws Exception {
        User target = admin(UserRole.ADMIN);
        User boss = admin(UserRole.SUPER_ADMIN);
        String bearer = jwtService.issueAccessToken(boss.getId(), boss.getRole(), Duration.ofMinutes(10), Instant.now());

        unlock(target, bearer, "{\"reason\":\"  \"}").andExpect(status().isBadRequest());
        unlock(target, bearer, "{}").andExpect(status().isBadRequest());
    }

    @Test
    void onlyASuperAdminCanUnlock() throws Exception {
        User target = admin(UserRole.ADMIN);
        User other = admin(UserRole.ADMIN);
        String bearer = jwtService.issueAccessToken(other.getId(), other.getRole(), Duration.ofMinutes(10), Instant.now());

        unlock(target, bearer, "{\"reason\":\"Locked out, verified by phone\"}").andExpect(status().isForbidden());
    }
}
