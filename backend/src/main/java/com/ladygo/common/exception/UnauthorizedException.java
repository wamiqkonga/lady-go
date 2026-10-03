package com.ladygo.common.exception;

public class UnauthorizedException extends ApiException {

    public UnauthorizedException() {
        super(ErrorCode.UNAUTHORIZED);
    }

    public UnauthorizedException(String safeMessage) {
        super(ErrorCode.UNAUTHORIZED, safeMessage);
    }
}
