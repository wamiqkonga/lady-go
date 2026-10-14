package com.ladygo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.auth.domain.OtpRequest;
import com.ladygo.auth.repository.OtpRequestRepository;
import com.ladygo.notifications.sms.LoggingSmsSender;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.security.crypto.OtpHasher;
import com.ladygo.testsupport.IntegrationTest;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
import com.ladygo.users.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class OtpLoginServiceIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(6000);

    @Autowired
    private OtpLoginService otpLoginService;
    @Autowired
    private OtpRequestRepository otpRequests;
    @Autowired
    private OtpHasher otpHasher;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CustomerRepository customers;

    private static String nextPhone() {
        return "+9194" + String.format("%08d", SEQ.incrementAndGet());
    }

    /** Inserts a code directly, bypassing the resend rate limit on a second real request - test setup only. */
    private String insertRawOtp(String phone, OtpPurpose purpose, String code) {
        OtpRequest entity = new OtpRequest();
        entity.setPhone(phone);
        entity.setPurpose(purpose);
        entity.setCodeHash(otpHasher.hash(code));
        entity.setExpiresAt(Instant.now().plus(5, ChronoUnit.MINUTES));
        otpRequests.saveAndFlush(entity);
        return code;
    }

    @Test
    void intendedRoleMustBeCustomerOrDriver() {
        assertThatThrownBy(() -> otpLoginService.requestOtp(nextPhone(), OtpPurpose.LOGIN, UserRole.ADMIN))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void phoneVerifyDoesNotIssueTokens() {
        String phone = nextPhone();
        otpLoginService.requestOtp(phone, OtpPurpose.PHONE_VERIFY, UserRole.CUSTOMER);
        String code = LoggingSmsSender.lastCodeFor(phone);

        Optional<TokenPair> result = otpLoginService.verifyOtp(
                phone, code, OtpPurpose.PHONE_VERIFY, UserRole.CUSTOMER, "device-1");

        assertThat(result).isEmpty();
    }

    @Test
    void loginIssuesTokensAndCreatesTheUserOnFirstSignIn() {
        String phone = nextPhone();
        otpLoginService.requestOtp(phone, OtpPurpose.LOGIN, UserRole.DRIVER);
        String code = LoggingSmsSender.lastCodeFor(phone);

        Optional<TokenPair> result = otpLoginService.verifyOtp(
                phone, code, OtpPurpose.LOGIN, UserRole.DRIVER, "device-1");

        assertThat(result).isPresent();
        assertThat(result.get().accessToken()).isNotBlank();
        assertThat(result.get().refreshToken()).isNotBlank();
    }

    @Test
    void firstCustomerSignInAlsoCreatesTheCustomerRow() {
        String phone = nextPhone();
        otpLoginService.requestOtp(phone, OtpPurpose.LOGIN, UserRole.CUSTOMER);
        String code = LoggingSmsSender.lastCodeFor(phone);

        otpLoginService.verifyOtp(phone, code, OtpPurpose.LOGIN, UserRole.CUSTOMER, "device-1");

        User user = users.findByPhone(phone).orElseThrow();
        assertThat(customers.findByUserId(user.getId())).isPresent();
    }

    @Test
    void firstDriverSignInDoesNotCreateACustomerRow() {
        String phone = nextPhone();
        otpLoginService.requestOtp(phone, OtpPurpose.LOGIN, UserRole.DRIVER);
        String code = LoggingSmsSender.lastCodeFor(phone);

        otpLoginService.verifyOtp(phone, code, OtpPurpose.LOGIN, UserRole.DRIVER, "device-1");

        User user = users.findByPhone(phone).orElseThrow();
        assertThat(customers.findByUserId(user.getId())).isEmpty();
    }

    @Test
    void existingUserOfADifferentRoleIsRejectedNotSwitched() {
        String phone = nextPhone();
        // First sign-up as a DRIVER.
        otpLoginService.requestOtp(phone, OtpPurpose.LOGIN, UserRole.DRIVER);
        String firstCode = LoggingSmsSender.lastCodeFor(phone);
        otpLoginService.verifyOtp(phone, firstCode, OtpPurpose.LOGIN, UserRole.DRIVER, "device-1");

        // Now the same phone tries to log in as a CUSTOMER (seeded directly: a real second requestOtp
        // this soon after the first would just hit the resend cooldown, which isn't what this test covers).
        String secondCode = insertRawOtp(phone, OtpPurpose.LOGIN, "222222");

        assertThatThrownBy(() -> otpLoginService.verifyOtp(
                phone, secondCode, OtpPurpose.LOGIN, UserRole.CUSTOMER, "device-1"))
                .isInstanceOf(OtpVerificationException.class);
    }

    /**
     * Adversarial review finding (CRITICAL): users.status was never checked on this path, so a
     * suspended or deleted account could still complete OTP login and receive a normal token pair.
     */
    @Test
    void suspendedUserCannotLogInEvenWithTheCorrectCode() {
        String phone = nextPhone();
        User user = new User();
        user.setPhone(phone);
        user.setRole(UserRole.CUSTOMER);
        user.setStatus(UserStatus.SUSPENDED);
        users.saveAndFlush(user);

        String code = insertRawOtp(phone, OtpPurpose.LOGIN, "333444");

        assertThatThrownBy(() -> otpLoginService.verifyOtp(phone, code, OtpPurpose.LOGIN, UserRole.CUSTOMER, "d1"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void deletedUserCannotLogInEvenWithTheCorrectCode() {
        String phone = nextPhone();
        User user = new User();
        user.setPhone(phone);
        user.setRole(UserRole.DRIVER);
        user.setStatus(UserStatus.DELETED);
        users.saveAndFlush(user);

        String code = insertRawOtp(phone, OtpPurpose.LOGIN, "333555");

        assertThatThrownBy(() -> otpLoginService.verifyOtp(phone, code, OtpPurpose.LOGIN, UserRole.DRIVER, "d1"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void otpRequestIgnoresTheChallengeHookWhenDisabled() {
        // Default off - a blank/missing challengeToken must not block anything.
        otpLoginService.requestOtp(nextPhone(), OtpPurpose.LOGIN, UserRole.CUSTOMER, "203.0.113.1", null);
    }

    @Test
    void otpRequestIsBlockedWhenTheChallengeIsEnabledAndTheTokenIsMissing() {
        jdbc.update("UPDATE configuration SET value = 'true' WHERE key = 'otp.challenge.enabled'");

        assertThatThrownBy(() -> otpLoginService.requestOtp(
                nextPhone(), OtpPurpose.LOGIN, UserRole.CUSTOMER, "203.0.113.1", null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void otpRequestSucceedsWhenTheChallengeIsEnabledAndATokenIsPresent() {
        jdbc.update("UPDATE configuration SET value = 'true' WHERE key = 'otp.challenge.enabled'");

        otpLoginService.requestOtp(nextPhone(), OtpPurpose.LOGIN, UserRole.CUSTOMER, "203.0.113.1", "some-token");
    }
}
