package com.ladygo.notifications.push;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Same defence in depth as {@code SmsSenderStartupGuard}: staging/prod never run the logging sender, and fcm needs its config. */
@Component
@EnableConfigurationProperties(PushProperties.class)
public class PushSenderStartupGuard {

    private static final Set<String> GUARDED_PROFILES = Set.of("staging", "prod");

    private final PushProperties properties;
    private final Environment environment;

    public PushSenderStartupGuard(PushProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void verify() {
        boolean guarded = Arrays.stream(environment.getActiveProfiles()).anyMatch(GUARDED_PROFILES::contains);
        if (guarded && "logging".equalsIgnoreCase(properties.provider())) {
            throw new IllegalStateException(
                    "ladygo.push.provider=logging must never be used under staging/prod; use fcm or disabled.");
        }
        if ("fcm".equalsIgnoreCase(properties.provider()) && !properties.fcmConfigured()) {
            throw new IllegalStateException(
                    "ladygo.push.provider=fcm needs LADYGO_FCM_PROJECT_ID and LADYGO_FCM_SERVICE_ACCOUNT_JSON.");
        }
    }
}
