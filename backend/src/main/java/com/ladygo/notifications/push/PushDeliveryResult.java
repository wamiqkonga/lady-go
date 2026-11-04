package com.ladygo.notifications.push;

public record PushDeliveryResult(Status status, String providerRef, String errorCode) {

    public enum Status {
        SENT,
        /** The provider says this device token is dead (app uninstalled, token rotated): delete it. */
        INVALID_TOKEN,
        FAILED
    }

    public static PushDeliveryResult sent(String providerRef) {
        return new PushDeliveryResult(Status.SENT, providerRef, null);
    }

    public static PushDeliveryResult invalidToken() {
        return new PushDeliveryResult(Status.INVALID_TOKEN, null, "INVALID_TOKEN");
    }

    public static PushDeliveryResult failed(String errorCode) {
        return new PushDeliveryResult(Status.FAILED, null, errorCode);
    }
}
