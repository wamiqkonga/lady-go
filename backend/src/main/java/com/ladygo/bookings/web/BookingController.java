package com.ladygo.bookings.web;

import com.ladygo.bookings.service.BookingCreationResult;
import com.ladygo.bookings.service.BookingService;
import com.ladygo.bookings.service.FareChangedException;
import com.ladygo.bookings.web.dto.BookingDetailResponse;
import com.ladygo.bookings.web.dto.BookingRequest;
import com.ladygo.bookings.web.dto.CancelBookingRequest;
import com.ladygo.bookings.web.dto.FareEstimateRequest;
import com.ladygo.bookings.web.dto.FareEstimateResponse;
import com.ladygo.common.api.ApiError;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.common.api.PageResponse;
import com.ladygo.common.exception.ErrorCode;
import com.ladygo.common.web.RequestIdFilter;
import com.ladygo.security.jwt.AuthPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/api/booking.md. */
@RestController
@RequestMapping("/api/v1/bookings")
@PreAuthorize("hasRole('CUSTOMER')")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping("/estimate")
    public ApiResponse<FareEstimateResponse> estimate(@Valid @RequestBody FareEstimateRequest request) {
        return ApiResponse.ok(bookingService.estimate(request));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BookingDetailResponse>> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody BookingRequest request) {
        BookingCreationResult result = bookingService.create(principal.userId(), request, idempotencyKey);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(result.booking()));
    }

    @GetMapping("/{id}")
    public ApiResponse<BookingDetailResponse> get(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return ApiResponse.ok(bookingService.get(principal.userId(), id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<BookingDetailResponse> cancel(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody CancelBookingRequest request) {
        return ApiResponse.ok(bookingService.cancel(principal.userId(), id, request.reason()));
    }

    @GetMapping
    public ApiResponse<PageResponse<BookingDetailResponse>> history(
            @AuthenticationPrincipal AuthPrincipal principal, Pageable pageable) {
        return ApiResponse.ok(bookingService.history(principal.userId(), pageable));
    }

    /**
     * docs/api/booking.md section 8: the one error that must carry data alongside the failure (the
     * fresh quote), so it gets its own handler instead of the generic {@code ApiException} one.
     */
    @ExceptionHandler(FareChangedException.class)
    public ResponseEntity<ApiResponse<FareEstimateResponse>> handleFareChanged(FareChangedException ex) {
        ApiError error = new ApiError(
                ErrorCode.FARE_CHANGED.name(), ex.getMessage(), List.of(), RequestIdFilter.current());
        ApiResponse<FareEstimateResponse> body = new ApiResponse<>(
                false, ex.getFreshEstimate(), error.message(), error, Instant.now());
        return ResponseEntity.status(ErrorCode.FARE_CHANGED.status()).body(body);
    }
}
