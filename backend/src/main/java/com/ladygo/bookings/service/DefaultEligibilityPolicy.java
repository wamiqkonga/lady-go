package com.ladygo.bookings.service;

import com.ladygo.bookings.web.dto.BookingRequest;
import com.ladygo.customers.domain.Customer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Placeholder only - unconditionally eligible. D5 (gender/age eligibility rules) is undecided and
 * there is no gender field on users/customers to check against; this is NOT a real policy.
 * Profile-gated the same way {@code MockSmsService} is, so it can never become the active bean in
 * staging/prod - see {@link EligibilityPolicyStartupGuard}.
 */
@Component
@Profile({"dev", "test"})
public class DefaultEligibilityPolicy implements EligibilityPolicy {

    @Override
    public EligibilityDecision checkCustomerEligibility(Customer customer) {
        return EligibilityDecision.allowed();
    }

    @Override
    public EligibilityDecision checkCompanions(BookingRequest request) {
        return EligibilityDecision.allowed();
    }
}
