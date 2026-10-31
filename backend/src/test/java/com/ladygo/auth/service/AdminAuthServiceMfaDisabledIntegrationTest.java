package com.ladygo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import com.ladygo.common.exception.UnauthorizedException;
import com.ladygo.testsupport.IntegrationTest;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** ladygo.mfa.required=false (pilot setting): staff sign in with email + password only. */
@IntegrationTest
@Transactional
@TestPropertySource(properties = "ladygo.mfa.required=false")
class AdminAuthServiceMfaDisabledIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(19000);
    private static final String PASSWORD = "InitialPass123!";

    @Autowired
    private AdminAuthService adminAuthService;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User freshBootstrapAdmin() {
        User user = new User();
        user.setPhone("+9197" + String.format("%08d", SEQ.incrementAndGet()));
        user.setEmail("nomfa" + SEQ.get() + "@ladygo.test");
        user.setRole(UserRole.SUPER_ADMIN);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setMustChangePassword(true);
        user.setMfaEnabled(false);
        return users.saveAndFlush(user);
    }

    @Test
    void passwordAloneAuthenticatesEvenForUnenrolledBootstrapAdmin() {
        User admin = freshBootstrapAdmin();

        AdminLoginOutcome outcome = adminAuthService.login(admin.getEmail(), PASSWORD, null, "device-1", "203.0.113.50");

        assertThat(outcome).isInstanceOf(AdminLoginOutcome.Authenticated.class);
    }

    @Test
    void wrongPasswordStillRejected() {
        User admin = freshBootstrapAdmin();

        assertThatThrownBy(() -> adminAuthService.login(admin.getEmail(), "wrong", null, "device-1", "203.0.113.51"))
                .isInstanceOf(UnauthorizedException.class);
    }
}
