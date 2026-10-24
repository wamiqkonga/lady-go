package com.ladygo.notifications.sms;

/**
 * Outcome of one {@link SmsSender#send} call. Deliberately carries no message body and no phone
 * number - only what's needed to know whether delivery was accepted and, on failure, why. Safe to log
 * or persist in full.
 */
public record SmsDeliveryResult(boolean accepted, String providerMessageId, String errorCode) {

    public static SmsDeliveryResult accepted(String providerMessageId) {
        return new SmsDeliveryResult(true, providerMessageId, null);
    }

    public static SmsDeliveryResult failed(String errorCode) {
        return new SmsDeliveryResult(false, null, errorCode);
    }
}
