package com.ladygo.common.exception;

public class DriverUnavailableException extends ApiException {

    public DriverUnavailableException() {
        super(ErrorCode.DRIVER_UNAVAILABLE);
    }

    public DriverUnavailableException(String safeMessage) {
        super(ErrorCode.DRIVER_UNAVAILABLE, safeMessage);
    }
}
