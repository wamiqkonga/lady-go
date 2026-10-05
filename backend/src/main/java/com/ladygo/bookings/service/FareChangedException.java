package com.ladygo.bookings.service;

import com.ladygo.bookings.web.dto.FareEstimateResponse;
import com.ladygo.common.exception.ApiException;
import com.ladygo.common.exception.ErrorCode;

/**
 * docs/api/booking.md section 2 (B2): the freshly-computed fare differs from the client's
 * {@code expectedFare} by more than the configured tolerance. Carries the fresh quote so the client
 * can show it and let the customer re-confirm - the one response that needs data alongside a
 * failure, handled by a controller-local {@code @ExceptionHandler} rather than the generic one.
 */
public class FareChangedException extends ApiException {

    private final FareEstimateResponse freshEstimate;

    public FareChangedException(FareEstimateResponse freshEstimate) {
        super(ErrorCode.FARE_CHANGED);
        this.freshEstimate = freshEstimate;
    }

    public FareEstimateResponse getFreshEstimate() {
        return freshEstimate;
    }
}
