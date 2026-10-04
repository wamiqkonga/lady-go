package com.ladygo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.auth.domain.OtpRequest;
import com.ladygo.auth.repository.OtpRequestRepository;
import com.ladygo.auth.sms.MockSmsService;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.security.crypto.OtpHasher;
import com.ladygo.testsupport.IntegrationTest;
import com.ladygo.users.domain.UserRole;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
        String code = MockSmsService.lastCodeFor(phone);

        Optional<TokenPair> result = otpLoginService.verifyOtp(
                phone, code, OtpPurpose.PHONE_VERIFY, UserRole.CUSTOMER, "device-1");

        assertThat(result).isEmpty();
    }

    @Test
    void loginIssuesTokensAndCreatesTheUserOnFirstSignIn() {
        String phone = nextPhone();
        otpLoginService.requestOtp(phone, OtpPurpose.LOGIN, UserRole.DRIVER);
        String code = MockSmsService.lastCodeFor(phone);

        Optional<TokenPair> result = otpLoginService.verifyOtp(
                phone, code, OtpPurpose.LOGIN, UserRole.DRIVER, "device-1");

        assertThat(result).isPresent();
        assertThat(result.get().accessToken()).isNotBlank();
        assertThat(result.get().refreshToken()).isNotBlank();
    }

    @Test
    void existingUserOfADifferentRoleIsRejectedNotSwitched() {
        String phone = nextPhone();
        // First sign-up as a DRIVER.
        otpLoginService.requestOtp(phone, OtpPurpose.LOGIN, UserRole.DRIVER);
        String firstCode = MockSmsService.lastCodeFor(phone);
        otpLoginService.verifyOtp(phone, firstCode, OtpPurpose.LOGIN, UserRole.DRIVER, "device-1");

        // Now the same phone tries to log in as a CUSTOMER (seeded directly: a real second requestOtp
        // this soon after the first would just hit the resend cooldown, which isn't what this test covers).
        String secondCode = insertRawOtp(phone, OtpPurpose.LOGIN, "222222");

        assertThatThrownBy(() -> otpLoginService.verifyOtp(
                phone, secondCode, OtpPurpose.LOGIN, UserRole.CUSTOMER, "device-1"))
                .isInstanceOf(OtpVerificationException.class);
    }
}
