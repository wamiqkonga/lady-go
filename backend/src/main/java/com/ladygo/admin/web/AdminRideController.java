package com.ladygo.admin.web;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.admin.service.AdminRideOversightService;
import com.ladygo.admin.web.dto.AdminRideDetailResponse;
import com.ladygo.admin.web.dto.AdminRideFlagResponse;
import com.ladygo.admin.web.dto.AdminRideFlagReviewRequest;
import com.ladygo.admin.web.dto.AdminRideSummaryResponse;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.common.api.PageResponse;
import com.ladygo.rides.domain.RideFlagType;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.security.net.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ride/flag oversight (docs/product/admin-web-design.md section 4). Read-only on rides themselves for
 * the pilot (decided: open question 2) - flag review is the one write action here.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AdminRideController {

    private final AdminRideOversightService service;
    private final ClientIpResolver clientIpResolver;

    public AdminRideController(AdminRideOversightService service, ClientIpResolver clientIpResolver) {
        this.service = service;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping("/rides")
    public ApiResponse<PageResponse<AdminRideSummaryResponse>> listRides(
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) UUID driverId,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) Instant createdAfter,
            @RequestParam(required = false) Instant createdBefore,
            @RequestParam(required = false) Boolean hasOpenFlags,
            Pageable pageable) {
        return ApiResponse.ok(service.listRides(
                status, driverId, customerId, createdAfter, createdBefore, hasOpenFlags, pageable));
    }

    @GetMapping("/rides/{bookingId}")
    public ApiResponse<AdminRideDetailResponse> getRide(@PathVariable UUID bookingId) {
        return ApiResponse.ok(service.getRideDetail(bookingId));
    }

    @GetMapping("/ride-flags")
    public ApiResponse<PageResponse<AdminRideFlagResponse>> listFlags(
            @RequestParam(required = false) RideFlagType type,
            @RequestParam(required = false) Boolean reviewed,
            Pageable pageable) {
        return ApiResponse.ok(service.listFlags(type, reviewed, pageable));
    }

    @PostMapping("/ride-flags/{id}/review")
    public ApiResponse<AdminRideFlagResponse> reviewFlag(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody AdminRideFlagReviewRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.ok(service.reviewFlag(
                id, principal.userId(), principal.role().name(), request.note(), clientIpResolver.resolve(httpRequest)));
    }
}
