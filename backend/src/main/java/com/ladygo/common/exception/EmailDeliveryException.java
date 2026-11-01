package com.ladygo.common.exception;

/** An email the action depends on could not be handed to the provider; the action was rolled back. */
public class EmailDeliveryException extends ApiException {

    public EmailDeliveryException() {
        super(ErrorCode.EMAIL_DELIVERY_FAILED);
    }
}
