package com.ladygo.auth.service;

import java.time.Instant;
import java.util.Optional;
import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
import com.ladygo.users.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OTP login/phone-verify orchestration for CUSTOMER/DRIVER (docs/security/auth-design.md section 4).
 * Staff authentication is a separate flow (password + TOTP) handled by {@link AdminLoginService}.
 */
@Service
public class OtpLoginService {

    private static final Logger log = LoggerFactory.getLogger(OtpLoginService.class);

    private final UserRepository users;
    private final CustomerRepository customers;
    private final OtpService otpService;
    private final RefreshTokenService refreshTokenService;
    private final AccessTokenIssuer accessTokenIssuer;

    public OtpLoginService(
            UserRepository users,
            CustomerRepository customers,
            OtpService otpService,
            RefreshTokenService refreshTokenService,
            AccessTokenIssuer accessTokenIssuer) {
        this.users = users;
        this.customers = customers;
        this.otpService = otpService;
        this.refreshTokenService = refreshTokenService;
        this.accessTokenIssuer = accessTokenIssuer;
    }

    public void requestOtp(String phone, OtpPurpose purpose, UserRole intendedRole) {
        requireCustomerOrDriver(intendedRole);
        otpService.requestOtp(phone, purpose);
    }

    /** The real entry point from {@code AuthController}, with the caller's resolved client IP for the
     *  per-IP SMS send cap (see {@code OtpService#requestOtp(String, OtpPurpose, String)}). */
    public void requestOtp(String phone, OtpPurpose purpose, UserRole intendedRole, String ip) {
        requireCustomerOrDriver(intendedRole);
        otpService.requestOtp(phone, purpose, ip);
    }

    /**
     * Returns tokens for {@code LOGIN}; empty for {@code PHONE_VERIFY} (nothing to issue, the phone is
     * just marked verified). Throws {@link com.ladygo.auth.service.OtpVerificationException} on any
     * OTP failure, and on an intendedRole/existing-role mismatch (section 4.3, OQ-8) - same generic
     * error either way, logged server-side for support follow-up.
     */
    @Transactional
    public Optional<TokenPair> verifyOtp(String phone, String code, OtpPurpose purpose, UserRole intendedRole,
            String deviceId) {
        requireCustomerOrDriver(intendedRole);
        otpService.verify(phone, code, purpose);

        User user = users.findByPhone(phone).orElse(null);
        if (user != null && user.getRole() != intendedRole) {
            log.info("operation=otp_role_mismatch phone_suffix={} existingRole={} intendedRole={}",
                    suffix(phone), user.getRole(), intendedRole);
            throw new OtpVerificationException();
        }
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            user.setRole(intendedRole);
            user = users.save(user);
            if (intendedRole == UserRole.CUSTOMER) {
                // docs/api/customer-profile.md section 1: every CUSTOMER needs a matching Customer
                // row (uq_customers_user) for the customers/me endpoints to resolve. Same transaction
                // as the User insert above, so a failure here rolls back the User too - no orphan.
                Customer customer = new Customer();
                customer.setUserId(user.getId());
                customers.save(customer);
            }
        } else if (user.getStatus() != UserStatus.ACTIVE) {
            // Adversarial review finding (CRITICAL): status was never checked on this path, so a
            // suspended/deleted account could still complete OTP login indefinitely.
            throw new ForbiddenException("This account is not active.");
        }

        if (purpose == OtpPurpose.PHONE_VERIFY) {
            user.setPhoneVerifiedAt(Instant.now());
            users.save(user);
            return Optional.empty();
        }

        user.setLastLoginAt(Instant.now());
        users.save(user);
        return Optional.of(issueTokens(user, deviceId));
    }

    private TokenPair issueTokens(User user, String deviceId) {
        String access = accessTokenIssuer.issue(user.getId(), user.getRole());
        String refresh = refreshTokenService.issue(user.getId(), deviceId);
        return new TokenPair(access, refresh);
    }

    private void requireCustomerOrDriver(UserRole role) {
        if (role != UserRole.CUSTOMER && role != UserRole.DRIVER) {
            throw new ValidationException("intendedRole must be CUSTOMER or DRIVER.");
        }
    }

    private static String suffix(String phone) {
        return phone.length() > 4 ? phone.substring(phone.length() - 4) : phone;
    }
}
