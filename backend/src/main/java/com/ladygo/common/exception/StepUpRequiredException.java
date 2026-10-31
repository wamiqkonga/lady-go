package com.ladygo.common.exception;

/** A destructive admin action needs a TOTP check newer than the step-up window. */
public class StepUpRequiredException extends ApiException {

    public StepUpRequiredException() {
        super(ErrorCode.STEP_UP_REQUIRED);
    }
}
