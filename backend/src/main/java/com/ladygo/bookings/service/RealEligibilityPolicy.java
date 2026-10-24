package com.ladygo.bookings.service;

import com.ladygo.bookings.web.dto.BookingRequest;
import com.ladygo.customers.domain.Customer;
import com.ladygo.verification.domain.SubjectType;
import com.ladygo.verification.service.SelfDeclarationService;
import org.springframework.stereotype.Component;

/**
 * The real D5 eligibility policy (docs/api/driver-onboarding.md section 0/4) - riders are never
 * admin-reviewed; eligibility rests only on having accepted the current self-declaration. The live
 * ride selfie is a separate, per-booking check enforced directly in {@link BookingService#create}
 * (not here - it's a request-scoped artifact, not an account-level policy decision).
 *
 * <p>{@link #checkCompanions} is deliberately unchanged from the roadmap default (unconditionally
 * allowed) - minors/companions are a separate D5 sub-question that hasn't been decided.
 */
@Component
public class RealEligibilityPolicy implements EligibilityPolicy {

    private final SelfDeclarationService selfDeclarations;

    public RealEligibilityPolicy(SelfDeclarationService selfDeclarations) {
        this.selfDeclarations = selfDeclarations;
    }

    @Override
    public EligibilityDecision checkCustomerEligibility(Customer customer) {
        if (!selfDeclarations.hasAcceptedCurrentVersion(SubjectType.CUSTOMER, customer.getId())) {
            return EligibilityDecision.denied(
                    "Accept the current self-declaration before booking (POST /customers/me/declaration).");
        }
        return EligibilityDecision.allowed();
    }

    @Override
    public EligibilityDecision checkCompanions(BookingRequest request) {
        return EligibilityDecision.allowed();
    }
}
