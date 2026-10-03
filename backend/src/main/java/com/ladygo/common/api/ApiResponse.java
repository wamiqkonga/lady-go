package com.ladygo.common.api;

import java.time.Instant;

/**
 * Standard response envelope for every API response.
 *
 * <pre>
 * { "success": true, "data": {}, "message": null, "error": null, "timestamp": "..." }
 * </pre>
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        ApiError error,
        Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null, Instant.now());
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, data, message, null, Instant.now());
    }

    public static ApiResponse<Void> failure(ApiError error) {
        return new ApiResponse<>(false, null, error.message(), error, Instant.now());
    }
}
