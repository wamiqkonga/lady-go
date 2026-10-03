package com.ladygo.common.exception;

public class PaymentException extends ApiException {

    public PaymentException() {
        super(ErrorCode.PAYMENT_FAILED);
    }

    public PaymentException(String safeMessage) {
        super(ErrorCode.PAYMENT_FAILED, safeMessage);
    }
}
