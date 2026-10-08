package com.ladygo.bookings.web;

import com.ladygo.bookings.service.BookingCreationResult;
import com.ladygo.bookings.service.BookingService;
import com.ladygo.bookings.service.FareChangedException;
import com.ladygo.bookings.web.dto.BookingDetailResponse;
import com.ladygo.bookings.web.dto.BookingRequest;
import com.ladygo.bookings.web.dto.CancelBookingRequest;
import com.ladygo.bookings.web.dto.DestinationChangeEstimateRequest;
import com.ladygo.bookings.web.dto.DestinationChangeRequest;
import com.ladygo.bookings.web.dto.FareEstimateRequest;
import com.ladygo.bookings.web.dto.FareEstimateResponse;
import com.ladygo.common.api.ApiError;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.common.api.PageResponse;
import com.ladygo.common.exception.ErrorCode;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.common.web.RequestIdFilter;
import com.ladygo.rides.service.RideLifecycleService;
import com.ladygo.rides.web.dto.DisputeCashRequest;
import com.ladygo.rides.web.dto.EndRideEarlyRequest;
import com.ladygo.rides.web.dto.RideFlagRespondRequest;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.verification.service.EligibilityReportService;
import com.ladygo.verification.web.dto.EligibilityReportRequest;
import com.ladygo.verification.web.dto.EligibilityReportResponse;
import jakarta.validation.Valid;
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

    /** Matches {@code bookings.idempotency_key varchar(100)} (V13) - checked here, not via a
     *  {@code @RequestHeader}-level {@code jakarta.validation} annotation, which this app's
     *  {@link com.ladygo.common.web.GlobalExceptionHandler} does not reliably map to a clean error
     *  (adversarial review findings, LOW/MEDIUM: a blank or oversized key previously surfaced as an
     *  opaque {@code 500}, the latter via a {@code DataIntegrityViolationException} that
     *  {@code BookingService.create} misread as a concurrent-insert race). */
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 100;

    private final BookingService bookingService;
    private final EligibilityReportService eligibilityReportService;
    private final RideLifecycleService rideLifecycleService;

    public BookingController(
            BookingService bookingService, EligibilityReportService eligibilityReportService,
            RideLifecycleService rideLifecycleService) {
        this.bookingService = bookingService;
        this.eligibilityReportService = eligibilityReportService;
        this.rideLifecycleService = rideLifecycleService;
    }

    @PostMapping("/estimate")
    public ApiResponse<FareEstimateResponse> estimate(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody FareEstimateRequest request) {
        return ApiResponse.ok(bookingService.estimate(principal.userId(), request));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BookingDetailResponse>> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BookingRequest request) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new ValidationException(
                    "Idempotency-Key must be present and at most " + IDEMPOTENCY_KEY_MAX_LENGTH + " characters.");
        }
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

    /** docs/api/ride-lifecycle.md section 13b-1 - fee-free rider cancel on a safety concern. */
    @PostMapping("/{id}/cancel-safety-concern")
    public ApiResponse<BookingDetailResponse> cancelForSafetyConcern(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody CancelBookingRequest request) {
        return ApiResponse.ok(bookingService.cancelForSafetyConcern(principal.userId(), id, request.reason()));
    }

    /** docs/api/ride-lifecycle.md section 13b-3 - no-mutation estimate step. */
    @PostMapping("/{id}/estimate-destination-change")
    public ApiResponse<FareEstimateResponse> estimateDestinationChange(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody DestinationChangeEstimateRequest request) {
        return ApiResponse.ok(bookingService.estimateDestinationChange(principal.userId(), id, request));
    }

    /** docs/api/ride-lifecycle.md section 13b-3 - the confirm step. */
    @PostMapping("/{id}/change-destination")
    public ApiResponse<BookingDetailResponse> changeDestination(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody DestinationChangeRequest request) {
        return ApiResponse.ok(bookingService.changeDestination(principal.userId(), id, request));
    }

    /** docs/api/ride-lifecycle.md section 13b-4 - rider-confirm-arrival gate bypass. */
    @PostMapping("/{id}/confirm-arrival")
    public ApiResponse<BookingDetailResponse> confirmArrival(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return ApiResponse.ok(rideLifecycleService.confirmArrival(principal.userId(), id));
    }

    /** docs/api/ride-lifecycle.md section 4 - either party may end a started ride early. */
    @PostMapping("/{id}/end-early")
    public ApiResponse<BookingDetailResponse> endEarly(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody EndRideEarlyRequest request) {
        return ApiResponse.ok(rideLifecycleService.endRideEarlyAsCustomer(principal.userId(), id, request.reason()));
    }

    /** docs/api/ride-lifecycle.md section 8 - within the dispute window only. */
    @PostMapping("/{id}/dispute-cash")
    public ApiResponse<BookingDetailResponse> disputeCash(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody DisputeCashRequest request) {
        return ApiResponse.ok(rideLifecycleService.disputeCash(principal.userId(), id, request.reason()));
    }

    /** docs/api/ride-lifecycle.md section 2.8 - the rider's response to a specific flag that prompted
     *  them (identified by flagId - a booking can carry more than one flag over its lifetime). */
    @PostMapping("/{id}/ride-flags/{flagId}/respond")
    public ApiResponse<Void> respondToFlag(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id, @PathVariable UUID flagId,
            @Valid @RequestBody RideFlagRespondRequest request) {
        rideLifecycleService.respondToFlag(principal.userId(), id, flagId, request.action());
        return ApiResponse.ok(null);
    }

    /**
     * docs/api/driver-onboarding.md section 5 - either party on the booking may report the other;
     * identified to admins, anonymous to the reported person, rate-limited, only between users who
     * shared this booking (enforced by resolving the subject FROM the booking, not a free-form id).
     */
    @PostMapping("/{id}/eligibility-report")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'DRIVER')")
    public ApiResponse<EligibilityReportResponse> reportEligibilityConcern(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody EligibilityReportRequest request) {
        return ApiResponse.ok(eligibilityReportService.report(principal.userId(), id, request.reason()));
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
