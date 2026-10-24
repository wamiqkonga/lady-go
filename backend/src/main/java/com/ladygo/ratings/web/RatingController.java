package com.ladygo.ratings.web;

import java.util.UUID;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.ratings.service.RatingService;
import com.ladygo.ratings.web.dto.DriverRatingSummaryResponse;
import com.ladygo.ratings.web.dto.RatingResponse;
import com.ladygo.ratings.web.dto.SubmitRatingRequest;
import com.ladygo.security.jwt.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** docs/api/driver-earnings-ratings.md section 4.3. */
@RestController
public class RatingController {

    private final RatingService service;

    public RatingController(RatingService service) {
        this.service = service;
    }

    /** Rider rates the driver of their completed ride. Repeating the identical rating returns 200 again; a different one is 409. */
    @PostMapping("/api/v1/bookings/{bookingId}/rating")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<RatingResponse> rateDriver(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID bookingId,
            @Valid @RequestBody SubmitRatingRequest request) {
        return ApiResponse.ok(service.rateDriver(principal.userId(), bookingId, request));
    }

    /** Driver rates the rider of their completed ride. Never shown to riders. */
    @PostMapping("/api/v1/drivers/me/bookings/{bookingId}/rating")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<RatingResponse> rateRider(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID bookingId,
            @Valid @RequestBody SubmitRatingRequest request) {
        return ApiResponse.ok(service.rateRider(principal.userId(), bookingId, request));
    }

    /** The driver's own rating aggregates: average, count, 1-5 distribution, tag counts. No comments. */
    @GetMapping("/api/v1/drivers/me/ratings/summary")
    @PreAuthorize("hasRole('DRIVER')")
    public ApiResponse<DriverRatingSummaryResponse> getMyRatingSummary(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.summaryForDriver(principal.userId()));
    }
}
