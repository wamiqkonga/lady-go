package com.ladygo.common.exception;

public class OutOfServiceAreaException extends ApiException {

    public OutOfServiceAreaException() {
        super(ErrorCode.OUT_OF_SERVICE_AREA);
    }
}
