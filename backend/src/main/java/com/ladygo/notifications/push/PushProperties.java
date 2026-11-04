package com.ladygo.notifications.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param provider {@code logging} (dev/test), {@code fcm}, or {@code disabled}
 * @param fcmProjectId the Firebase project id (not secret)
 * @param fcmServiceAccountJson the full service-account key JSON, from the environment only (secret)
 */
@ConfigurationProperties(prefix = "ladygo.push")
public record PushProperties(String provider, String fcmProjectId, String fcmServiceAccountJson) {

    public boolean fcmConfigured() {
        return fcmProjectId != null && !fcmProjectId.isBlank()
                && fcmServiceAccountJson != null && !fcmServiceAccountJson.isBlank();
    }
}
