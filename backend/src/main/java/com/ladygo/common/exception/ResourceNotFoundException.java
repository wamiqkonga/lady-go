package com.ladygo.common.exception;

public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException() {
        super(ErrorCode.RESOURCE_NOT_FOUND);
    }

    public ResourceNotFoundException(String safeMessage) {
        super(ErrorCode.RESOURCE_NOT_FOUND, safeMessage);
    }
}
