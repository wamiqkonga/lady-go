package com.ladygo.security.ratelimit;

import com.ladygo.common.exception.ApiException;
import com.ladygo.common.exception.ErrorCode;

public class RateLimitExceededException extends ApiException {

    public RateLimitExceededException() {
        super(ErrorCode.RATE_LIMITED);
    }
}
