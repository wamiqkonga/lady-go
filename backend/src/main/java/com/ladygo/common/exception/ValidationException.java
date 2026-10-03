package com.ladygo.common.exception;

public class ValidationException extends ApiException {

    public ValidationException() {
        super(ErrorCode.VALIDATION_FAILED);
    }

    public ValidationException(String safeMessage) {
        super(ErrorCode.VALIDATION_FAILED, safeMessage);
    }
}
