package com.ladygo.auth.service;

import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.common.exception.UnauthorizedException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.customers.domain.Customer;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
import com.ladygo.users.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rider accounts: register with phone, email and a password (the email is proven by a code sent to it), log in with
 * email or phone plus password, and reset a forgotten password by emailed code. The phone number is required and
 * becomes the account's identity but is not itself verified (same trade-off as the email OTP channel,
 * docs/development/email-otp-setup.md).
 */
@Service
public class CustomerAuthService {

    private static final Logger log = LoggerFactory.getLogger(CustomerAuthService.class);

    /** A real BCrypt hash of nothing in particular, compared against when the account is unknown, so a miss takes as
     *  long as a wrong password and the response time does not reveal which accounts exist. */
    private static final String DUMMY_HASH_SOURCE = "ladygo-timing-equaliser";

    private final UserRepository users;
    private final CustomerRepository customers;
    private final OtpService otpService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RateLimiter rateLimiter;
    private final ConfigurationService config;
    private final String dummyHash;

    public CustomerAuthService(UserRepository users, CustomerRepository customers, OtpService otpService,
            PasswordEncoder passwordEncoder, RefreshTokenService refreshTokenService,
            AccessTokenIssuer accessTokenIssuer, RateLimiter rateLimiter, ConfigurationService config) {
        this.users = users;
        this.customers = customers;
        this.otpService = otpService;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.accessTokenIssuer = accessTokenIssuer;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.dummyHash = passwordEncoder.encode(DUMMY_HASH_SOURCE);
    }

    /** At least 8 characters with a letter and a digit (the DTO bounds the length to BCrypt's 72 bytes). */
    static void requireStrongPassword(String password) {
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        if (password.length() < 8 || !letter || !digit) {
            throw new ValidationException("Choose a password of at least 8 characters with a letter and a number.");
        }
    }

    private static String normaliseEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Step 1: emails a code, unless the phone or email already belongs to an account. */
    public void requestRegistrationOtp(String phone, String email, String ip) {
        String normalised = normaliseEmail(email);
        if (users.findByPhone(phone).isPresent() || users.findByEmailIgnoreCase(normalised).isPresent()) {
            log.info("operation=customer_register_otp result=rejected reason=exists");
            throw new AccountExistsException();
        }
        otpService.requestEmailOtp(phone, OtpPurpose.REGISTER, ip, normalised);
    }

    /** Step 2: checks the code and creates the account with the chosen password. */
    @Transactional
    public TokenPair register(String phone, String email, String code, String password, String deviceId) {
        requireStrongPassword(password);
        String normalised = normaliseEmail(email);
        String otpEmail = otpService.verify(phone, code, OtpPurpose.REGISTER);
        if (otpEmail == null || !otpEmail.equalsIgnoreCase(normalised)) {
            throw new OtpVerificationException();
        }
        if (users.findByPhone(phone).isPresent() || users.findByEmailIgnoreCase(normalised).isPresent()) {
            throw new AccountExistsException();
        }
        User user = new User();
        user.setPhone(phone);
        user.setEmail(normalised);
        user.setRole(UserRole.CUSTOMER);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setLastLoginAt(Instant.now());
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new AccountExistsException(); // lost a race with a concurrent registration
        }
        Customer customer = new Customer();
        customer.setUserId(user.getId());
        customers.save(customer);
        log.info("operation=customer_register result=created");
        return issueTokens(user, deviceId);
    }

    @Transactional
    public TokenPair login(String identifier, String password, String deviceId, String ip) {
        String key = identifier.trim().toLowerCase(Locale.ROOT);
        checkLoginRateLimit(key, ip);
        User user = (key.contains("@") ? users.findByEmailIgnoreCase(key) : users.findByPhone(identifier.trim()))
                .filter(u -> u.getRole() == UserRole.CUSTOMER)
                .orElse(null);
        String hash = user != null && user.getPasswordHash() != null ? user.getPasswordHash() : dummyHash;
        boolean matches = passwordEncoder.matches(password, hash);
        if (user == null || user.getPasswordHash() == null || !matches) {
            throw new UnauthorizedException();
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("This account is not active.");
        }
        rateLimiter.release(identifierBucket(key));
        user.setLastLoginAt(Instant.now());
        users.save(user);
        return issueTokens(user, deviceId);
    }

    /**
     * Always looks successful from outside, so the endpoint cannot be used to find out which emails have accounts.
     * (A per-IP cap still applies to everyone, and a per-account send cap is swallowed rather than reported.)
     */
    public void requestPasswordReset(String email, String ip) {
        if (ip != null && !ip.isBlank()
                && !rateLimiter.tryConsume("password_forgot:ip:" + ip, 10, Duration.ofHours(1))) {
            throw new RateLimitExceededException();
        }
        String normalised = normaliseEmail(email);
        User user = users.findByEmailIgnoreCase(normalised)
                .filter(u -> u.getRole() == UserRole.CUSTOMER && u.getStatus() == UserStatus.ACTIVE)
                .orElse(null);
        if (user == null) {
            log.info("operation=password_forgot result=no_account");
            return;
        }
        try {
            otpService.requestEmailOtp(user.getPhone(), OtpPurpose.PASSWORD_RESET, ip, normalised);
        } catch (RateLimitExceededException e) {
            log.info("operation=password_forgot result=rate_limited");
        }
    }

    /** Sets the new password and signs her out everywhere: a reset usually means someone else may have had it. */
    @Transactional
    public void resetPassword(String email, String code, String newPassword) {
        requireStrongPassword(newPassword);
        String normalised = normaliseEmail(email);
        User user = users.findByEmailIgnoreCase(normalised)
                .filter(u -> u.getRole() == UserRole.CUSTOMER && u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(OtpVerificationException::new);
        String otpEmail = otpService.verify(user.getPhone(), code, OtpPurpose.PASSWORD_RESET);
        if (otpEmail == null || !otpEmail.equalsIgnoreCase(normalised)) {
            throw new OtpVerificationException();
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        users.save(user);
        refreshTokenService.revokeAll(user.getId());
        rateLimiter.release(identifierBucket(normalised));
        log.info("operation=password_reset result=done");
    }

    private TokenPair issueTokens(User user, String deviceId) {
        return new TokenPair(accessTokenIssuer.issue(user.getId(), user.getRole()),
                refreshTokenService.issue(user.getId(), deviceId));
    }

    private void checkLoginRateLimit(String key, String ip) {
        int maxAttempts = config.getInt("customer.login.max.attempts", 10);
        Duration window = config.getMinutes("customer.login.window.minutes", Duration.ofMinutes(15));
        boolean identifierOk = rateLimiter.tryConsume(identifierBucket(key), maxAttempts, window);
        boolean ipOk = ip == null || ip.isBlank()
                || rateLimiter.tryConsume("customer_login:ip:" + ip, maxAttempts * 5, window);
        if (!identifierOk || !ipOk) {
            throw new RateLimitExceededException();
        }
    }

    private static String identifierBucket(String key) {
        return "customer_login:id:" + key;
    }
}
