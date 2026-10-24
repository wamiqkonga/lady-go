package com.ladygo.common.exception;

public class LimitExceededException extends ApiException {

    public LimitExceededException() {
        super(ErrorCode.LIMIT_EXCEEDED);
    }

    public LimitExceededException(String safeMessage) {
        super(ErrorCode.LIMIT_EXCEEDED, safeMessage);
    }
}
