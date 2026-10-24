package com.ladygo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.auth.domain.OtpRequest;
import com.ladygo.auth.repository.OtpRequestRepository;
import com.ladygo.notifications.sms.LoggingSmsSender;
import com.ladygo.security.crypto.OtpHasher;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.testsupport.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Autowired
    private JdbcTemplate jdbc;

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
        String code = LoggingSmsSender.lastCodeFor(phone);

        assertThat(code).isNotNull();
        otpService.verify(phone, code, OtpPurpose.LOGIN); // does not throw
    }

    @Test
    void wrongCodeIsRejectedAndCountsAsAnAttempt() {
        String phone = nextPhone();
        otpService.requestOtp(phone, OtpPurpose.LOGIN);

        assertThatThrownBy(() -> otpService.verify(phone, "000000", OtpPurpose.LOGIN))
                .isInstanceOf(OtpVerificationException.class);

        String realCode = LoggingSmsSender.lastCodeFor(phone);
        otpService.verify(phone, realCode, OtpPurpose.LOGIN); // still allowed, attempts not exhausted
    }

    @Test
    void codeCannotBeUsedTwice() {
        String phone = nextPhone();
        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        String code = LoggingSmsSender.lastCodeFor(phone);

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
        String secondCode = LoggingSmsSender.lastCodeFor(phone);

        assertThatThrownBy(() -> otpService.verify(phone, firstCode, OtpPurpose.LOGIN))
                .isInstanceOf(OtpVerificationException.class);
        otpService.verify(phone, secondCode, OtpPurpose.LOGIN); // the latest code still works
    }

    @Test
    void exhaustingAttemptsLocksOutEvenTheCorrectCode() {
        String phone = nextPhone();
        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        String code = LoggingSmsSender.lastCodeFor(phone);

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

    /** Relaxes the resend limiter (which bounds how often ONE number can ask at all) so these tests
     *  exercise only the send caps below it, not an unrelated limit tripping first. */
    private void relaxResendLimiter() {
        jdbc.update("UPDATE configuration SET value = '0' WHERE key = 'otp.resend.cooldown.seconds'");
        jdbc.update("UPDATE configuration SET value = '1000' WHERE key = 'otp.resend.max.per.hour'");
    }

    @Test
    void perNumberDailySendCapBlocksFurtherSendsToTheSameNumber() {
        relaxResendLimiter();
        jdbc.update("UPDATE configuration SET value = '2' WHERE key = 'sms.send.max_per_number.daily'");
        String phone = nextPhone();

        otpService.requestOtp(phone, OtpPurpose.LOGIN);
        otpService.requestOtp(phone, OtpPurpose.LOGIN);

        assertThatThrownBy(() -> otpService.requestOtp(phone, OtpPurpose.LOGIN))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void perIpDailySendCapBlocksFurtherSendsFromTheSameIpAcrossDifferentNumbers() {
        relaxResendLimiter();
        jdbc.update("UPDATE configuration SET value = '2' WHERE key = 'sms.send.max_per_ip.daily'");
        String ip = "203.0.113.42";

        otpService.requestOtp(nextPhone(), OtpPurpose.LOGIN, ip);
        otpService.requestOtp(nextPhone(), OtpPurpose.LOGIN, ip);

        assertThatThrownBy(() -> otpService.requestOtp(nextPhone(), OtpPurpose.LOGIN, ip))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void globalDailySendCapBlocksAndLogsAnAlert() {
        relaxResendLimiter();
        jdbc.update("UPDATE configuration SET value = '2' WHERE key = 'sms.send.max_global.daily'");

        Logger logger = (Logger) LoggerFactory.getLogger(OtpService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ALL);

        try {
            otpService.requestOtp(nextPhone(), OtpPurpose.LOGIN);
            otpService.requestOtp(nextPhone(), OtpPurpose.LOGIN);

            assertThatThrownBy(() -> otpService.requestOtp(nextPhone(), OtpPurpose.LOGIN))
                    .isInstanceOf(RateLimitExceededException.class);

            assertThat(appender.list).anySatisfy(event ->
                    assertThat(event.getFormattedMessage())
                            .contains("sms_global_daily_cap_exceeded")
                            .contains("alert=true"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
