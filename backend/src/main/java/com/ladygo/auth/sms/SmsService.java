package com.ladygo.auth.sms;

import java.time.Duration;

/**
 * SMS delivery SPI (docs/architecture/backend-architecture.md section 7, NotificationChannel family).
 * A real provider adapter and {@link MockSmsService} both implement this; nothing else in the
 * codebase talks to a carrier directly.
 */
public interface SmsService {

    void sendOtp(String phoneE164, String code, Duration validFor);
}
