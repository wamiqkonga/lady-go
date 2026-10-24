package com.ladygo.notifications.email;

/**
 * Provider-agnostic email delivery SPI, the email counterpart of
 * {@link com.ladygo.notifications.sms.SmsSender}. Used by the MVP 1 email-OTP channel
 * ({@code ladygo.otp.channel=email}). Implementations: {@link LoggingEmailSender} (dev/test) and
 * {@link SesEmailSender} (staging/prod).
 */
public interface EmailSender {

    /** @return true if the provider accepted the message (not a delivery guarantee). */
    boolean send(String toAddress, String subject, String textBody);
}
