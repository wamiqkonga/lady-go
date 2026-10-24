package com.ladygo.bookings.web;

import java.net.URI;
import java.util.UUID;
import com.ladygo.bookings.service.BookingService;
import com.ladygo.bookings.service.RiderSelfieDeliveryService;
import com.ladygo.bookings.web.dto.BookingDetailResponse;
import com.ladygo.bookings.web.dto.CancelBookingRequest;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.security.jwt.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/api/driver-onboarding.md section 4/5 - driver-side booking actions. */
@RestController
@RequestMapping("/api/v1/drivers/me/bookings")
@PreAuthorize("hasRole('DRIVER')")
public class DriverBookingController {

    private final BookingService bookingService;
    private final RiderSelfieDeliveryService riderSelfieDeliveryService;

    public DriverBookingController(
            BookingService bookingService, RiderSelfieDeliveryService riderSelfieDeliveryService) {
        this.bookingService = bookingService;
        this.riderSelfieDeliveryService = riderSelfieDeliveryService;
    }

    /** docs/api/matching.md section 6 - exact pickup address becomes visible here, to the accepting
     *  driver only, once {@code BookingService.getForDriver} confirms ownership. */
    @GetMapping("/{id}")
    public ApiResponse<BookingDetailResponse> get(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable java.util.UUID id) {
        return ApiResponse.ok(bookingService.getForDriver(principal.userId(), id));
    }

    /** Cancel without penalty on an eligibility concern - auto-creates an eligibility report. */
    @PostMapping("/{id}/cancel-eligibility-concern")
    public ApiResponse<BookingDetailResponse> cancelForEligibilityConcern(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody CancelBookingRequest request) {
        return ApiResponse.ok(bookingService.cancelForEligibilityConcern(principal.userId(), id, request.reason()));
    }

    /** A short-lived signed URL to a watermarked derivative - visible only between acceptance and ride start. */
    @GetMapping("/{id}/rider-selfie")
    public ApiResponse<URI> riderSelfie(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return ApiResponse.ok(riderSelfieDeliveryService.deliverFor(principal.userId(), id));
    }
}
