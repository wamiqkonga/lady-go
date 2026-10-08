package com.ladygo.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes with their HTTP status.
 */
public enum ErrorCode {
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication is required."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have permission to perform this action."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request is invalid."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "This method is not supported for this endpoint."),
    BOOKING_INVALID_STATE(HttpStatus.CONFLICT, "The booking is not in a state that allows this action."),
    PAYMENT_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "The payment could not be processed."),
    DRIVER_UNAVAILABLE(HttpStatus.CONFLICT, "The driver is not available."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please try again later."),
    OUT_OF_SERVICE_AREA(HttpStatus.UNPROCESSABLE_ENTITY, "Lady Go is not available in this area yet."),
    FARE_CHANGED(HttpStatus.UNPROCESSABLE_ENTITY, "The fare has changed since your estimate."),
    MAPS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Maps service is temporarily unavailable."),
    LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "A configured limit has been reached."),
    CONCURRENT_UPDATE(HttpStatus.CONFLICT, "This was just updated by someone else. Please retry."),
    PAYMENT_METHOD_UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "This payment method is not available yet."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
