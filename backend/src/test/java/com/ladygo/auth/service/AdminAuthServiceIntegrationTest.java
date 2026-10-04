package com.ladygo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.common.exception.UnauthorizedException;
import com.ladygo.security.crypto.MfaSecretCipher;
import com.ladygo.security.crypto.TotpService;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.testsupport.IntegrationTest;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
import com.ladygo.users.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end through the real service + Postgres: bootstrap-style staff row -> forced password
 * change -> MFA enrol -> confirm -> real TOTP login, plus replay rejection and login rate limiting
 * (docs/security/auth-design.md section 3 and section 11).
 */
@IntegrationTest
@Transactional
class AdminAuthServiceIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(9000);
    private static final String INITIAL_PASSWORD = "InitialPass123!";
    private static final String NEW_PASSWORD = "NewPassword456!";

    @Autowired
    private AdminAuthService adminAuthService;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private TotpService totpService;
    @Autowired
    private AuditLogRepository auditLogs;
    @Autowired
    private MfaSecretCipher mfaSecretCipher;
    @Autowired
    private JdbcTemplate jdbc;

    private User newStaffPendingEnrolment(UserRole role) {
        User user = new User();
        user.setPhone("+9196" + String.format("%08d", SEQ.incrementAndGet()));
        user.setEmail("admin" + SEQ.get() + "@ladygo.test");
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode(INITIAL_PASSWORD));
        user.setMustChangePassword(true);
        user.setMfaEnabled(false);
        return users.saveAndFlush(user);
    }

    private AuthPrincipal principalFrom(String enrolmentToken) {
        return jwtService.parse(enrolmentToken).orElseThrow();
    }

    @Test
    void fullBootstrapToRealLoginFlow() {
        User admin = newStaffPendingEnrolment(UserRole.SUPER_ADMIN);

        // 1) Login with the initial password: must-change-password -> enrolment required, both steps.
        AdminLoginOutcome outcome1 = adminAuthService.login(
                admin.getEmail(), INITIAL_PASSWORD, null, "device-1", "203.0.113.1");
        assertThat(outcome1).isInstanceOf(AdminLoginOutcome.EnrolmentRequired.class);
        var enrolment1 = (AdminLoginOutcome.EnrolmentRequired) outcome1;
        assertThat(enrolment1.remainingSteps()).containsExactly("PASSWORD_CHANGE", "MFA_ENROLL");
        AuthPrincipal principal1 = principalFrom(enrolment1.enrolmentToken());
        assertThat(principal1.passwordChanged()).isFalse();

        // 2) Change the password using that enrolment token.
        adminAuthService.changePassword(principal1, INITIAL_PASSWORD, NEW_PASSWORD);

        // 3) The SAME (now stale) token cannot start MFA enrolment - its pwdOk claim is frozen at false.
        assertThatThrownBy(() -> adminAuthService.startMfaEnrollment(principal1))
                .isInstanceOf(ForbiddenException.class);

        // 4) A fresh login (new password) now only asks for MFA_ENROLL.
        AdminLoginOutcome outcome2 = adminAuthService.login(
                admin.getEmail(), NEW_PASSWORD, null, "device-1", "203.0.113.1");
        var enrolment2 = (AdminLoginOutcome.EnrolmentRequired) outcome2;
        assertThat(enrolment2.remainingSteps()).containsExactly("MFA_ENROLL");
        AuthPrincipal principal2 = principalFrom(enrolment2.enrolmentToken());
        assertThat(principal2.passwordChanged()).isTrue();

        // 5) Start and confirm MFA enrolment with a real TOTP code for the pending secret.
        AdminAuthService.MfaEnrollStart start = adminAuthService.startMfaEnrollment(principal2);
        long step = totpService.currentStep(Instant.now());
        String confirmCode = totpService.generateCode(start.secret(), step);
        adminAuthService.confirmMfaEnrollment(principal2, confirmCode);

        // 6) Re-using the same code (same step) anywhere else must be rejected as replay.
        assertThatThrownBy(() -> adminAuthService.login(admin.getEmail(), NEW_PASSWORD, confirmCode, "device-1",
                "203.0.113.1")).isInstanceOf(UnauthorizedException.class);

        // 7) A fresh code for the next step completes a real login with tokens.
        String loginCode = totpService.generateCode(start.secret(), step + 1);
        AdminLoginOutcome outcome3 = adminAuthService.login(
                admin.getEmail(), NEW_PASSWORD, loginCode, "device-1", "203.0.113.1");
        assertThat(outcome3).isInstanceOf(AdminLoginOutcome.Authenticated.class);
        var authenticated = (AdminLoginOutcome.Authenticated) outcome3;
        assertThat(authenticated.tokens().accessToken()).isNotBlank();
        assertThat(authenticated.tokens().refreshToken()).isNotBlank();

        // 8) That same login code cannot be replayed either.
        assertThatThrownBy(() -> adminAuthService.login(admin.getEmail(), NEW_PASSWORD, loginCode, "device-1",
                "203.0.113.1")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void wrongPasswordIsRejectedGenerically() {
        User admin = newStaffPendingEnrolment(UserRole.ADMIN);
        assertThatThrownBy(() -> adminAuthService.login(admin.getEmail(), "wrong-password", null, "device-1",
                "203.0.113.2")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void unknownEmailIsRejectedTheSameWayAsWrongPassword() {
        assertThatThrownBy(() -> adminAuthService.login("nobody@ladygo.test", "whatever", null, "device-1",
                "203.0.113.3")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void loginLockoutTripsAfterTheConfiguredAttempts() {
        User admin = newStaffPendingEnrolment(UserRole.ADMIN);
        String ip = "203.0.113.4";
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> adminAuthService.login(admin.getEmail(), "wrong", null, "device-1", ip))
                    .isInstanceOf(UnauthorizedException.class);
        }
        assertThatThrownBy(() -> adminAuthService.login(admin.getEmail(), "wrong", null, "device-1", ip))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void resetMfaClearsEnrolmentAndAudits() {
        User target = newStaffPendingEnrolment(UserRole.ADMIN);
        target.setMustChangePassword(false);
        target.setMfaEnabled(true);
        target.setMfaSecretEnc("cipher-text-not-real");
        users.saveAndFlush(target);

        UUID actorId = UUID.randomUUID();
        adminAuthService.resetMfa(actorId, UserRole.SUPER_ADMIN, target.getId(), "203.0.113.5");

        User reloaded = users.findById(target.getId()).orElseThrow();
        assertThat(reloaded.isMfaEnabled()).isFalse();
        assertThat(reloaded.getMfaSecretEnc()).isNull();

        assertThat(auditLogs.findAll()).anySatisfy(entry -> {
            assertThat(entry.getAction()).isEqualTo("ADMIN_MFA_RESET");
            assertThat(entry.getTargetId()).isEqualTo(target.getId().toString());
            assertThat(entry.getActorId()).isEqualTo(actorId);
        });
    }

    /**
     * Adversarial review finding (CRITICAL): users.status was never checked on this path, so a
     * suspended staff account with a correct password (and even a correct TOTP code) could still log in.
     */
    @Test
    void suspendedAdminCannotLogInEvenWithCorrectCredentials() {
        User admin = newStaffPendingEnrolment(UserRole.ADMIN);
        admin.setMustChangePassword(false);
        admin.setMfaEnabled(true);
        admin.setStatus(UserStatus.SUSPENDED);
        users.saveAndFlush(admin);

        assertThatThrownBy(() -> adminAuthService.login(
                admin.getEmail(), INITIAL_PASSWORD, "000000", "device-1", "203.0.113.7"))
                .isInstanceOf(ForbiddenException.class);
    }

    /**
     * Adversarial review finding (MEDIUM): the login rate limit used to consume a slot on every call,
     * including successful ones, so enough legitimate logins in one window self-locked the account.
     */
    @Test
    void successfulLoginsDoNotSelfLockTheAccount() {
        jdbc.update("UPDATE configuration SET value = '2' WHERE key = 'admin.login.max.attempts' AND scope = 'GLOBAL'");

        User admin = newStaffPendingEnrolment(UserRole.ADMIN);
        admin.setMustChangePassword(false);
        admin.setMfaEnabled(true);
        String secret = totpService.generateSecret();
        admin.setMfaSecretEnc(mfaSecretCipher.encrypt(secret));
        users.saveAndFlush(admin);

        String ip = "203.0.113.8";
        long ref = totpService.currentStep(Instant.now());
        // Three successful logins, more than the max-attempts=2 threshold just configured above - none
        // of them should count against it, because none of them is a failure.
        for (long step = ref - 1; step <= ref + 1; step++) {
            String code = totpService.generateCode(secret, step);
            AdminLoginOutcome outcome = adminAuthService.login(
                    admin.getEmail(), INITIAL_PASSWORD, code, "device-1", ip);
            assertThat(outcome).isInstanceOf(AdminLoginOutcome.Authenticated.class);
        }
    }
}
