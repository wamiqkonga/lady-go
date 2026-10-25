package com.ladygo.auth.service;

import com.ladygo.common.exception.ApiException;
import com.ladygo.common.exception.ErrorCode;

/**
 * Email sign-in codes are only sent when the phone and email belong together (see
 * {@code OtpLoginService#requestOtp}). The message is the same whichever side is already registered, so it
 * tells the person what to do without saying which of the two is on file.
 */
public class OtpEmailMismatchException extends ApiException {

    public OtpEmailMismatchException() {
        super(ErrorCode.OTP_EMAIL_MISMATCH);
    }
}
