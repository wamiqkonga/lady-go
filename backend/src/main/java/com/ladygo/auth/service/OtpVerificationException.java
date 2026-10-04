package com.ladygo.auth.service;

import com.ladygo.common.exception.ApiException;
import com.ladygo.common.exception.ErrorCode;

/**
 * Deliberately generic: covers "no such code", "expired", "wrong code" and "attempts exhausted" alike,
 * so the response never reveals which case applies (docs/security/auth-design.md section 4).
 */
public class OtpVerificationException extends ApiException {

    public OtpVerificationException() {
        super(ErrorCode.VALIDATION_FAILED, "The code is invalid or has expired.");
    }
}
