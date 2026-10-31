package com.ladygo.notifications.push;

/**
 * Provider-agnostic push delivery SPI, like {@code SmsSender}: nothing outside this package talks to Firebase.
 * Implementations: {@link LoggingPushSender} (dev/test), {@link FcmPushSender} (staging/prod), and
 * {@link DisabledPushSender} (explicitly no push yet).
 */
public interface PushSender {

    PushDeliveryResult send(String deviceToken, PushMessage message);
}
