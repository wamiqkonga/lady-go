package com.ladygo.rides.web;

import java.util.UUID;
import com.ladygo.bookings.web.dto.BookingDetailResponse;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.rides.service.RideLifecycleService;
import com.ladygo.rides.web.dto.EndRideEarlyRequest;
import com.ladygo.rides.web.dto.StartRideRequest;
import com.ladygo.security.jwt.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/api/ride-lifecycle.md - driver-side ride actions. */
@RestController
@RequestMapping("/api/v1/drivers/me/rides")
@PreAuthorize("hasRole('DRIVER')")
public class RideLifecycleController {

    private final RideLifecycleService rideLifecycleService;

    public RideLifecycleController(RideLifecycleService rideLifecycleService) {
        this.rideLifecycleService = rideLifecycleService;
    }

    @PostMapping("/{id}/arriving")
    public ApiResponse<BookingDetailResponse> arriving(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return ApiResponse.ok(rideLifecycleService.markArriving(principal.userId(), id));
    }

    @PostMapping("/{id}/arrived")
    public ApiResponse<BookingDetailResponse> arrived(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return ApiResponse.ok(rideLifecycleService.markArrived(principal.userId(), id));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<BookingDetailResponse> start(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody StartRideRequest request) {
        return ApiResponse.ok(rideLifecycleService.startRide(principal.userId(), id, request.pin()));
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<BookingDetailResponse> complete(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return ApiResponse.ok(rideLifecycleService.completeRide(principal.userId(), id));
    }

    @PostMapping("/{id}/end-early")
    public ApiResponse<BookingDetailResponse> endEarly(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody EndRideEarlyRequest request) {
        return ApiResponse.ok(rideLifecycleService.endRideEarlyAsDriver(principal.userId(), id, request.reason()));
    }

    @PostMapping("/{id}/confirm-cash-collected")
    public ApiResponse<BookingDetailResponse> confirmCashCollected(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return ApiResponse.ok(rideLifecycleService.confirmCashCollected(principal.userId(), id));
    }
}
