package com.ladygo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.notifications.email.LoggingEmailSender;
import com.ladygo.testsupport.IntegrationTest;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** MVP 1 email-OTP channel ({@code ladygo.otp.channel=email}). */
@IntegrationTest
@Transactional
@TestPropertySource(properties = "ladygo.otp.channel=email")
class OtpEmailChannelIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(7000);

    @Autowired
    private OtpLoginService otpLoginService;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    private static String nextPhone() {
        return "+9195" + String.format("%08d", SEQ.incrementAndGet());
    }

    private static String nextEmail() {
        return "driver-" + UUID.randomUUID() + "@example.test";
    }

    private int otpRowsFor(String phone) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT count(*) FROM otp_requests WHERE phone = ?", Integer.class, phone);
    }

    @Test
    void codeIsEmailedAndLoginBindsTheEmailToTheUser() {
        String phone = nextPhone();
        String email = nextEmail();

        otpLoginService.requestOtp(phone, OtpPurpose.LOGIN, UserRole.DRIVER, "1.2.3.4", null, email);
        String code = LoggingEmailSender.lastCodeFor(email);
        assertThat(code).matches("[0-9]{6}");

        var tokens = otpLoginService.verifyOtp(phone, code, OtpPurpose.LOGIN, UserRole.DRIVER, "device-1");

        assertThat(tokens).isPresent();
        var user = users.findByPhone(phone).orElseThrow();
        assertThat(user.getEmail()).isEqualTo(email.toLowerCase());
        assertThat(user.getPhoneVerifiedAt()).as("phone is not verified by an email code").isNull();
    }

    @Test
    void emailIsRequired() {
        assertThatThrownBy(() -> otpLoginService.requestOtp(
                nextPhone(), OtpPurpose.LOGIN, UserRole.DRIVER, "1.2.3.4", null, " "))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void phoneVerifyIsRejectedBecauseEmailCannotVerifyAPhone() {
        assertThatThrownBy(() -> otpLoginService.requestOtp(
                nextPhone(), OtpPurpose.PHONE_VERIFY, UserRole.DRIVER, "1.2.3.4", null, nextEmail()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void anotherEmailCannotRequestACodeForAnAlreadyClaimedPhone() {
        String phone = nextPhone();
        String owner = nextEmail();
        otpLoginService.requestOtp(phone, OtpPurpose.LOGIN, UserRole.DRIVER, "1.2.3.4", null, owner);
        otpLoginService.verifyOtp(phone, LoggingEmailSender.lastCodeFor(owner), OtpPurpose.LOGIN,
                UserRole.DRIVER, "device-1");
        int rowsBefore = otpRowsFor(phone);

        String attacker = nextEmail();
        assertThatThrownBy(() -> otpLoginService.requestOtp(
                phone, OtpPurpose.LOGIN, UserRole.DRIVER, "5.6.7.8", null, attacker))
                .isInstanceOf(OtpEmailMismatchException.class);

        assertThat(otpRowsFor(phone)).as("no code created for the mismatching email").isEqualTo(rowsBefore);
        assertThat(LoggingEmailSender.lastCodeFor(attacker)).isNull();
    }

    @Test
    void anEmailAlreadyBoundToAnotherPhoneCannotBeReused() {
        String phoneA = nextPhone();
        String email = nextEmail();
        otpLoginService.requestOtp(phoneA, OtpPurpose.LOGIN, UserRole.DRIVER, "1.2.3.4", null, email);
        otpLoginService.verifyOtp(phoneA, LoggingEmailSender.lastCodeFor(email), OtpPurpose.LOGIN,
                UserRole.DRIVER, "device-1");

        String phoneB = nextPhone();
        assertThatThrownBy(() -> otpLoginService.requestOtp(
                phoneB, OtpPurpose.LOGIN, UserRole.DRIVER, "1.2.3.4", null, email))
                .isInstanceOf(OtpEmailMismatchException.class);

        assertThat(otpRowsFor(phoneB)).isZero();
    }

    @Test
    void anExistingAccountWithNoBoundEmailCannotBeClaimedByEmail() {
        String phone = nextPhone();
        var legacy = new com.ladygo.users.domain.User();
        legacy.setPhone(phone);
        legacy.setRole(UserRole.DRIVER);
        users.saveAndFlush(legacy);

        String attacker = nextEmail();
        assertThatThrownBy(() -> otpLoginService.requestOtp(
                phone, OtpPurpose.LOGIN, UserRole.DRIVER, "5.6.7.8", null, attacker))
                .isInstanceOf(OtpEmailMismatchException.class);

        assertThat(otpRowsFor(phone)).isZero();
        assertThat(LoggingEmailSender.lastCodeFor(attacker)).isNull();
    }

    @Test
    void theMismatchErrorTellsTheDriverWhatToDoAndIsTheSameForEveryKindOfMismatch() {
        String phoneA = nextPhone();
        String emailA = nextEmail();
        otpLoginService.requestOtp(phoneA, OtpPurpose.LOGIN, UserRole.DRIVER, "1.2.3.4", null, emailA);
        otpLoginService.verifyOtp(phoneA, LoggingEmailSender.lastCodeFor(emailA), OtpPurpose.LOGIN,
                UserRole.DRIVER, "device-1");

        // Known phone with another email, and known email with another phone: one message, so the
        // response does not say WHICH of the two is already registered.
        String otherEmail = nextEmail();
        String otherPhone = nextPhone();
        String phoneSide = catchMessage(() -> otpLoginService.requestOtp(
                phoneA, OtpPurpose.LOGIN, UserRole.DRIVER, "1.2.3.4", null, otherEmail));
        String emailSide = catchMessage(() -> otpLoginService.requestOtp(
                otherPhone, OtpPurpose.LOGIN, UserRole.DRIVER, "1.2.3.4", null, emailA));

        assertThat(phoneSide).isEqualTo(emailSide);
        assertThat(phoneSide).contains("same phone number and email");
    }

    private static String catchMessage(Runnable action) {
        try {
            action.run();
        } catch (OtpEmailMismatchException ex) {
            return ex.getMessage();
        }
        throw new AssertionError("expected OtpEmailMismatchException");
    }

    @Test
    void sameEmailCanLogInAgain() {
        String phone = nextPhone();
        String email = nextEmail();
        otpLoginService.requestOtp(phone, OtpPurpose.LOGIN, UserRole.DRIVER, "1.2.3.4", null, email);
        otpLoginService.verifyOtp(phone, LoggingEmailSender.lastCodeFor(email), OtpPurpose.LOGIN,
                UserRole.DRIVER, "device-1");
        // The resend cooldown applies; clear the limiter-backed state the way the other OTP tests do.
        jdbc.update("DELETE FROM auth_rate_limits");

        otpLoginService.requestOtp(phone, OtpPurpose.LOGIN, UserRole.DRIVER, "1.2.3.4", null, email.toUpperCase());

        assertThat(otpRowsFor(phone)).isEqualTo(2);
    }
}
