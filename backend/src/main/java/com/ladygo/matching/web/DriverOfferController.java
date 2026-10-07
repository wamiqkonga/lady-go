package com.ladygo.matching.web;

import java.util.UUID;
import com.ladygo.bookings.web.dto.BookingDetailResponse;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.matching.service.MatchingService;
import com.ladygo.matching.web.dto.OfferRejectRequest;
import com.ladygo.matching.web.dto.OfferResponse;
import com.ladygo.security.jwt.AuthPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/api/matching.md section 6. */
@RestController
@RequestMapping("/api/v1/drivers/me/offers")
@PreAuthorize("hasRole('DRIVER')")
public class DriverOfferController {

    private final MatchingService matchingService;

    public DriverOfferController(MatchingService matchingService) {
        this.matchingService = matchingService;
    }

    /** HTTP polling fallback, in case the WebSocket push never arrived. */
    @GetMapping("/current")
    public ApiResponse<OfferResponse> currentOffer(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(matchingService.getCurrentOffer(principal.userId()));
    }

    @PostMapping("/{id}/accept")
    public ApiResponse<BookingDetailResponse> accept(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return ApiResponse.ok(matchingService.accept(principal.userId(), id));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @RequestBody(required = false) OfferRejectRequest request) {
        matchingService.reject(principal.userId(), id, request == null ? null : request.reason());
        return ApiResponse.ok(null);
    }
}
