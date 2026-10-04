package com.ladygo.auth.sms;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Defence in depth for docs/security/auth-design.md section 9: {@link MockSmsService} is already
 * {@code @Profile({"dev","test"})}, so staging/prod cannot normally wire it, and anything requiring a
 * single {@code SmsService} bean (e.g. the OTP flow) already fails to start with no real provider
 * configured. This guard turns a future misconfiguration (someone loosening that profile annotation)
 * into a clear, explicit startup failure instead of a silent "it just happens to work".
 */
@Component
public class SmsStartupGuard {

    private static final Set<String> GUARDED_PROFILES = Set.of("staging", "prod");

    private final List<SmsService> smsServices;
    private final Environment environment;

    public SmsStartupGuard(List<SmsService> smsServices, Environment environment) {
        this.smsServices = smsServices;
        this.environment = environment;
    }

    @PostConstruct
    void verifyNoMockInGuardedProfiles() {
        boolean guarded = Arrays.stream(environment.getActiveProfiles()).anyMatch(GUARDED_PROFILES::contains);
        if (!guarded) {
            return;
        }
        boolean mockPresent = smsServices.stream().anyMatch(MockSmsService.class::isInstance);
        if (mockPresent) {
            throw new IllegalStateException(
                    "MockSmsService must never be active under staging/prod; configure a real SmsService.");
        }
    }
}
