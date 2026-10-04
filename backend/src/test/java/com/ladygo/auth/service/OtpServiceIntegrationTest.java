package com.ladygo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.auth.domain.OtpRequest;
import com.ladygo.auth.repository.OtpRequestRepository;
import com.ladygo.auth.sms.MockSmsService;
import com.ladygo.security.crypto.OtpHasher;
import com.ladygo.testsupport.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Exercises the real atomic-update repository queries against Postgres, not a mock. */
@IntegrationTest
@Transactional
class OtpServiceIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(5000);

    @Autowired
    private OtpService otpService;
    @Autowired
    private OtpRequestRepository otpRequests;
    @Autowired
    private OtpHasher otpHasher;

    private static String nextPhone() {
        return "+9199" + String.format("%08d", SEQ.incrementAndGet());
    }

    /** Inserts a code directly, bypassing OtpService's own resend rate limit - for test setup only. */
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
    void requestThenVerifyWithTheRealCodeSucceeds() {
        String phone = nextPhone();
        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        String code = MockSmsService.lastCodeFor(phone);

        assertThat(code).isNotNull();
        otpService.verify(phone, code, OtpPurpose.LOGIN); // does not throw
    }

    @Test
    void wrongCodeIsRejectedAndCountsAsAnAttempt() {
        String phone = nextPhone();
        otpService.requestOtp(phone, OtpPurpose.LOGIN);

        assertThatThrownBy(() -> otpService.verify(phone, "000000", OtpPurpose.LOGIN))
                .isInstanceOf(OtpVerificationException.class);

        String realCode = MockSmsService.lastCodeFor(phone);
        otpService.verify(phone, realCode, OtpPurpose.LOGIN); // still allowed, attempts not exhausted
    }

    @Test
    void codeCannotBeUsedTwice() {
        String phone = nextPhone();
        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        String code = MockSmsService.lastCodeFor(phone);

        otpService.verify(phone, code, OtpPurpose.LOGIN);

        assertThatThrownBy(() -> otpService.verify(phone, code, OtpPurpose.LOGIN))
                .isInstanceOf(OtpVerificationException.class);
    }

    @Test
    void newRequestInvalidatesTheEarlierUnconsumedCode() {
        String phone = nextPhone();
        // Seeded directly so this test isn't also exercising (and colliding with) the resend cooldown.
        String firstCode = insertRawOtp(phone, OtpPurpose.LOGIN, "111111");

        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        String secondCode = MockSmsService.lastCodeFor(phone);

        assertThatThrownBy(() -> otpService.verify(phone, firstCode, OtpPurpose.LOGIN))
                .isInstanceOf(OtpVerificationException.class);
        otpService.verify(phone, secondCode, OtpPurpose.LOGIN); // the latest code still works
    }

    @Test
    void exhaustingAttemptsLocksOutEvenTheCorrectCode() {
        String phone = nextPhone();
        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        String code = MockSmsService.lastCodeFor(phone);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> otpService.verify(phone, "000000", OtpPurpose.LOGIN))
                    .isInstanceOf(OtpVerificationException.class);
        }

        assertThatThrownBy(() -> otpService.verify(phone, code, OtpPurpose.LOGIN))
                .isInstanceOf(OtpVerificationException.class);
    }

    @Test
    void unknownPhoneOrPurposeIsRejectedGenerically() {
        assertThatThrownBy(() -> otpService.verify(nextPhone(), "123456", OtpPurpose.LOGIN))
                .isInstanceOf(OtpVerificationException.class);
    }
}
