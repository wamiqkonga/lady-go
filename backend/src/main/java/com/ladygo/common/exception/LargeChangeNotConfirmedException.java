package com.ladygo.common.exception;

/** A fare change moves a field by more than the configured threshold and the request did not confirm it. */
public class LargeChangeNotConfirmedException extends ApiException {

    public LargeChangeNotConfirmedException(String safeMessage) {
        super(ErrorCode.LARGE_CHANGE_NOT_CONFIRMED, safeMessage);
    }
}
