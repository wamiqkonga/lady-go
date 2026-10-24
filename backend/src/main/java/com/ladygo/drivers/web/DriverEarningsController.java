package com.ladygo.drivers.web;

import java.time.LocalDate;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.common.api.PageResponse;
import com.ladygo.drivers.service.DriverEarningsService;
import com.ladygo.drivers.web.dto.DriverEarningsResponse;
import com.ladygo.drivers.web.dto.DriverRideHistoryItemResponse;
import com.ladygo.security.jwt.AuthPrincipal;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** docs/api/driver-earnings-ratings.md sections 4.1 and 4.2. */
@RestController
@RequestMapping("/api/v1/drivers/me")
@PreAuthorize("hasRole('DRIVER')")
public class DriverEarningsController {

    private final DriverEarningsService service;

    public DriverEarningsController(DriverEarningsService service) {
        this.service = service;
    }

    /** Newest first, last 90 days, the caller's own rides only; {@code page} (from 0) and {@code size} (max 50). */
    @GetMapping("/rides")
    public ApiResponse<PageResponse<DriverRideHistoryItemResponse>> listMyRides(
            @AuthenticationPrincipal AuthPrincipal principal, Pageable pageable) {
        return ApiResponse.ok(service.history(principal.userId(), pageable));
    }

    /** {@code from}/{@code to} are inclusive dates in the city's time zone (max 31 days; default: current week). */
    @GetMapping("/earnings")
    public ApiResponse<DriverEarningsResponse> getMyEarnings(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.earnings(principal.userId(), from, to));
    }
}
