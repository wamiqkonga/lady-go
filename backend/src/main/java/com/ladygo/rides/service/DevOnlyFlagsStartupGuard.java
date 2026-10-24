package com.ladygo.rides.service;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Same defence-in-depth shape as {@code SmsSenderStartupGuard} (docs/security/auth-design.md section 9),
 * for two dev/test-only convenience flags introduced in docs/api/ride-lifecycle.md section 13b: a
 * driver location update may skip the (otherwise mandatory) GPS accuracy field, and a reserved
 * Phase-8 payment-stub flag must never mark a payment succeeded for real. Turns a future
 * misconfiguration - either flag left {@code true} outside dev/test - into a clear, explicit startup
 * failure instead of a silent hole in a real environment.
 */
@Component
public class DevOnlyFlagsStartupGuard {

    private static final Set<String> GUARDED_PROFILES = Set.of("staging", "prod");

    private final Environment environment;
    private final boolean allowMissingLocationAccuracy;
    private final boolean paymentsStubEnabled;

    public DevOnlyFlagsStartupGuard(
            Environment environment,
            @Value("${ladygo.dev.allow_missing_location_accuracy:false}") boolean allowMissingLocationAccuracy,
            @Value("${ladygo.payments.stub_enabled:false}") boolean paymentsStubEnabled) {
        this.environment = environment;
        this.allowMissingLocationAccuracy = allowMissingLocationAccuracy;
        this.paymentsStubEnabled = paymentsStubEnabled;
    }

    @PostConstruct
    void verifyNoDevOnlyFlagsInGuardedProfiles() {
        boolean guarded = Arrays.stream(environment.getActiveProfiles()).anyMatch(GUARDED_PROFILES::contains);
        if (!guarded) {
            return;
        }
        if (allowMissingLocationAccuracy) {
            throw new IllegalStateException(
                    "ladygo.dev.allow_missing_location_accuracy must never be true under staging/prod.");
        }
        if (paymentsStubEnabled) {
            throw new IllegalStateException("ladygo.payments.stub_enabled must never be true under staging/prod.");
        }
    }
}
