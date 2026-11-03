package com.ladygo.auth.service;

import com.ladygo.common.exception.ApiException;
import com.ladygo.common.exception.ErrorCode;

/** A driver may not use the app yet (or any more): the code says why, so the app can show the right message. */
public class DriverLoginBlockedException extends ApiException {

    public DriverLoginBlockedException(ErrorCode code) {
        super(code);
    }
}
