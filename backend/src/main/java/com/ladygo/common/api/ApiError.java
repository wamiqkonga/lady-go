package com.ladygo.common.api;

import java.util.List;

/**
 * Safe, client-facing error description. Never contains stack traces or internal details.
 */
public record ApiError(
        String code,
        String message,
        List<FieldViolation> details,
        String requestId) {

    public record FieldViolation(String field, String message) {
    }

    public static ApiError of(String code, String message, String requestId) {
        return new ApiError(code, message, List.of(), requestId);
    }
}
