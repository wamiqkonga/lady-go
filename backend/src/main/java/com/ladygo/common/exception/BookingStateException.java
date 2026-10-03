package com.ladygo.common.exception;

public class BookingStateException extends ApiException {

    public BookingStateException() {
        super(ErrorCode.BOOKING_INVALID_STATE);
    }

    public BookingStateException(String safeMessage) {
        super(ErrorCode.BOOKING_INVALID_STATE, safeMessage);
    }
}
