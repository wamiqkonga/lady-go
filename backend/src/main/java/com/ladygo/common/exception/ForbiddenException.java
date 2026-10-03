package com.ladygo.common.exception;

public class ForbiddenException extends ApiException {

    public ForbiddenException() {
        super(ErrorCode.FORBIDDEN);
    }

    public ForbiddenException(String safeMessage) {
        super(ErrorCode.FORBIDDEN, safeMessage);
    }
}
