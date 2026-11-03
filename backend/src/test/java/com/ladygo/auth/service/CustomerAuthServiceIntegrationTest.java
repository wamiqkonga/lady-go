package com.ladygo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.common.exception.UnauthorizedException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.notifications.email.LoggingEmailSender;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.testsupport.IntegrationTest;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
import com.ladygo.users.repository.UserRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Rider registration (email code + password), password login, and password reset. */
@IntegrationTest
@Transactional
class CustomerAuthServiceIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(4000);
    private static final String PASSWORD = "Sunrise2026";

    @Autowired
    private CustomerAuthService service;
    @Autowired
    private UserRepository users;
    @Autowired
    private CustomerRepository customers;
    @Autowired
    private RefreshTokenService refreshTokens;

    private static String nextPhone() {
        return "+9196" + String.format("%08d", SEQ.incrementAndGet());
    }

    private static String nextEmail() {
        return "rider-" + UUID.randomUUID() + "@example.test";
    }

    /** Registers a rider end to end and returns her phone and email. */
    private String[] registered() {
        String phone = nextPhone();
        String email = nextEmail();
        service.requestRegistrationOtp(phone, email, "1.2.3.4");
        service.register(phone, email, LoggingEmailSender.lastCodeFor(email), PASSWORD, "device-1");
        return new String[] {phone, email};
    }

    @Test
    void registeringWithAnEmailedCodeCreatesAnAccountAndSignsHerIn() {
        String phone = nextPhone();
        String email = nextEmail();

        service.requestRegistrationOtp(phone, email, "1.2.3.4");
        String code = LoggingEmailSender.lastCodeFor(email);
        assertThat(code).matches("[0-9]{6}");
        TokenPair tokens = service.register(phone, email.toUpperCase(), code, PASSWORD, "device-1");

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        User user = users.findByPhone(phone).orElseThrow();
        assertThat(user.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(user.getEmail()).isEqualTo(email.toLowerCase());
        assertThat(user.getPasswordHash()).isNotBlank().isNotEqualTo(PASSWORD);
        assertThat(customers.findByUserId(user.getId())).isPresent();
    }

    @Test
    void thePhoneAndEmailAreBothRefusedWhenAlreadyRegistered() {
        String[] first = registered();

        assertThatThrownBy(() -> service.requestRegistrationOtp(first[0], nextEmail(), "1.2.3.4"))
                .isInstanceOf(AccountExistsException.class);
        assertThatThrownBy(() -> service.requestRegistrationOtp(nextPhone(), first[1].toUpperCase(), "1.2.3.4"))
                .isInstanceOf(AccountExistsException.class);
    }

    @Test
    void aWrongOrForeignCodeDoesNotRegister() {
        String phone = nextPhone();
        String email = nextEmail();
        service.requestRegistrationOtp(phone, email, "1.2.3.4");
        String code = LoggingEmailSender.lastCodeFor(email);
        String wrong = code.equals("000000") ? "111111" : "000000";

        assertThatThrownBy(() -> service.register(phone, email, wrong, PASSWORD, "d"))
                .isInstanceOf(OtpVerificationException.class);
        // The code was sent for this email; using it with a different email must fail too.
        assertThatThrownBy(() -> service.register(phone, nextEmail(), code, PASSWORD, "d"))
                .isInstanceOf(OtpVerificationException.class);
        assertThat(users.findByPhone(phone)).isEmpty();
    }

    @Test
    void weakPasswordsAreRejected() {
        for (String weak : new String[] {"short1", "onlyletters", "12345678"}) {
            assertThatThrownBy(() -> service.register(nextPhone(), nextEmail(), "123456", weak, "d"))
                    .as(weak).isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void emailOrPhonePlusPasswordLogsHerIn() {
        String[] account = registered();

        assertThat(service.login(account[1], PASSWORD, "d", "1.2.3.4").accessToken()).isNotBlank();
        assertThat(service.login(account[0], PASSWORD, "d", "1.2.3.4").accessToken()).isNotBlank();
        assertThat(service.login(account[1].toUpperCase(), PASSWORD, "d", "1.2.3.4").accessToken()).isNotBlank();
    }

    @Test
    void aWrongPasswordAndAnUnknownAccountLookTheSame() {
        String[] account = registered();

        assertThatThrownBy(() -> service.login(account[1], "Wrong1234", "d", "1.2.3.4"))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.login(nextEmail(), PASSWORD, "d", "1.2.3.4"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aDriversAccountCannotLogInThroughTheRiderEndpoint() {
        User driver = new User();
        driver.setPhone(nextPhone());
        driver.setEmail(nextEmail());
        driver.setRole(UserRole.DRIVER);
        users.saveAndFlush(driver);

        assertThatThrownBy(() -> service.login(driver.getEmail(), PASSWORD, "d", "1.2.3.4"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aSuspendedAccountCannotLogIn() {
        String[] account = registered();
        User user = users.findByPhone(account[0]).orElseThrow();
        user.setStatus(UserStatus.SUSPENDED);
        users.saveAndFlush(user);

        assertThatThrownBy(() -> service.login(account[1], PASSWORD, "d", "1.2.3.4"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void repeatedWrongPasswordsAreRateLimited() {
        String[] account = registered();
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> service.login(account[1], "Wrong1234", "d", "1.2.3.4"))
                    .isInstanceOf(UnauthorizedException.class);
        }
        assertThatThrownBy(() -> service.login(account[1], PASSWORD, "d", "1.2.3.4"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void forgottenPasswordIsResetByAnEmailedCodeAndSignsHerOutEverywhere() {
        String[] account = registered();
        String oldRefresh = service.login(account[1], PASSWORD, "phone-1", "1.2.3.4").refreshToken();

        service.requestPasswordReset(account[1], "1.2.3.4");
        String code = LoggingEmailSender.lastCodeFor(account[1]);
        service.resetPassword(account[1], code, "Moonlight2027");

        assertThatThrownBy(() -> service.login(account[1], PASSWORD, "d", "1.2.3.4"))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(service.login(account[1], "Moonlight2027", "d", "1.2.3.4").accessToken()).isNotBlank();
        assertThatThrownBy(() -> refreshTokens.rotate(oldRefresh)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aResetCodeCannotBeReusedOrGuessed() {
        String[] account = registered();
        service.requestPasswordReset(account[1], "1.2.3.4");
        String code = LoggingEmailSender.lastCodeFor(account[1]);
        String wrong = code.equals("000000") ? "111111" : "000000";

        assertThatThrownBy(() -> service.resetPassword(account[1], wrong, "Moonlight2027"))
                .isInstanceOf(OtpVerificationException.class);
        service.resetPassword(account[1], code, "Moonlight2027");
        assertThatThrownBy(() -> service.resetPassword(account[1], code, "Another2028"))
                .isInstanceOf(OtpVerificationException.class);
    }

    @Test
    void forgottenPasswordForAnUnknownEmailLooksSuccessfulAndSendsNothing() {
        String stranger = nextEmail();

        service.requestPasswordReset(stranger, "1.2.3.4");

        assertThat(LoggingEmailSender.lastCodeFor(stranger)).isNull();
    }

    @Test
    void aResetForAnUnknownEmailFailsLikeAWrongCode() {
        assertThatThrownBy(() -> service.resetPassword(nextEmail(), "123456", "Moonlight2027"))
                .isInstanceOf(OtpVerificationException.class);
    }
}
