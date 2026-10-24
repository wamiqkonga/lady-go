package com.ladygo.common.exception;

/** The caller already rated this ride with different content (an identical resubmit is idempotent instead). */
public class AlreadyRatedException extends ApiException {

    public AlreadyRatedException() {
        super(ErrorCode.ALREADY_RATED);
    }
}
