package com.ladygo.common.exception;

public class PaymentMethodUnavailableException extends ApiException {

    public PaymentMethodUnavailableException() {
        super(ErrorCode.PAYMENT_METHOD_UNAVAILABLE);
    }
}
