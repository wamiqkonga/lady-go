package com.ladygo.maps;

import com.ladygo.common.exception.ApiException;
import com.ladygo.common.exception.ErrorCode;

/** The maps provider failed (timeout, 5xx, quota) - not the caller's fault. See docs/api/maps-service.md section 5. */
public class MapsServiceException extends ApiException {

    public MapsServiceException(Throwable cause) {
        super(ErrorCode.MAPS_UNAVAILABLE);
        if (cause != null) {
            initCause(cause);
        }
    }
}
