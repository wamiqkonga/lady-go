package com.ladygo.bookings.service;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Mirrors {@code SmsStartupGuard} exactly (docs/security/auth-design.md section 9): D5 is undecided
 * (docs/api/booking.md section 7), so staging/prod must refuse to start on the placeholder policy
 * rather than silently shipping with no real eligibility check in a real environment.
 */
@Component
public class EligibilityPolicyStartupGuard {

    private static final Set<String> GUARDED_PROFILES = Set.of("staging", "prod");

    private final List<EligibilityPolicy> policies;
    private final Environment environment;

    public EligibilityPolicyStartupGuard(List<EligibilityPolicy> policies, Environment environment) {
        this.policies = policies;
        this.environment = environment;
    }

    @PostConstruct
    void verifyNoPlaceholderInGuardedProfiles() {
        boolean guarded = Arrays.stream(environment.getActiveProfiles()).anyMatch(GUARDED_PROFILES::contains);
        if (!guarded) {
            return;
        }
        boolean placeholderPresent = policies.stream().anyMatch(DefaultEligibilityPolicy.class::isInstance);
        if (placeholderPresent) {
            throw new IllegalStateException(
                    "DefaultEligibilityPolicy must never be active under staging/prod; D5 must be decided "
                            + "and a real EligibilityPolicy configured first.");
        }
    }
}
