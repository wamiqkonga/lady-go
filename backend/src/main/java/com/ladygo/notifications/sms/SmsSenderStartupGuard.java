package com.ladygo.notifications.sms;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Defence in depth, same pattern as {@code EligibilityPolicy}/{@code DevOnlyFlagsStartupGuard}:
 * {@link LoggingSmsSender} is already {@code @Profile({"dev","test"})}, so staging/prod cannot
 * normally wire it, and {@link HttpSmsSender}'s required properties already come from
 * {@code LADYGO_SMS_*} env vars with no default in those profiles (Spring fails to even start the
 * context if one is missing). This guard turns a future misconfiguration (someone loosening that
 * profile annotation, or a default creeping back into application-staging.yml/application-prod.yml)
 * into a clear, explicit startup failure instead of a silent "it just happens to work" - or worse, a
 * silently-broken OTP flow that no one notices until a real user can't log in.
 */
@Component
@EnableConfigurationProperties(SmsSenderProperties.class)
public class SmsSenderStartupGuard {

    private static final Set<String> GUARDED_PROFILES = Set.of("staging", "prod");

    private final List<SmsSender> smsSenders;
    private final SmsSenderProperties properties;
    private final Environment environment;

    public SmsSenderStartupGuard(List<SmsSender> smsSenders, SmsSenderProperties properties, Environment environment) {
        this.smsSenders = smsSenders;
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void verifyRealSenderConfiguredInGuardedProfiles() {
        boolean guarded = Arrays.stream(environment.getActiveProfiles()).anyMatch(GUARDED_PROFILES::contains);
        if (!guarded) {
            return;
        }
        if ("email".equalsIgnoreCase(environment.getProperty("ladygo.otp.channel", "sms"))) {
            // MVP 1 email-OTP channel: SMS is not used, so its provider config is not required. The
            // email sender's own required properties fail startup if missing (SesEmailSender).
            return;
        }
        boolean loggingSenderPresent = smsSenders.stream().anyMatch(LoggingSmsSender.class::isInstance);
        if (loggingSenderPresent) {
            throw new IllegalStateException(
                    "LoggingSmsSender must never be active under staging/prod; configure a real SmsSender.");
        }
        if (!properties.isFullyConfigured()) {
            throw new IllegalStateException(
                    "HttpSmsSender is missing required configuration (base URL, auth header "
                            + "name/value, or body template) under staging/prod - set the "
                            + "LADYGO_SMS_* environment variables (see docs/development/sms-provider-setup.md).");
        }
    }
}
