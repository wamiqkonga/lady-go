package com.ladygo.common.exception;

/** A destructive admin action was attempted by an account with no authenticator enrolled. */
public class MfaEnrolmentRequiredException extends ApiException {

    public MfaEnrolmentRequiredException() {
        super(ErrorCode.MFA_ENROLMENT_REQUIRED);
    }
}
