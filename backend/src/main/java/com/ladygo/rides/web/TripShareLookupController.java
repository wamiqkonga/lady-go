package com.ladygo.rides.web;

import com.ladygo.common.api.ApiResponse;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.rides.service.TripShareService;
import com.ladygo.rides.web.dto.TripShareLookupResponse;
import com.ladygo.security.net.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/api/safety.md section 4 - public, unauthenticated (see {@code PUBLIC_ENDPOINTS} in
 * SecurityConfig). The token is carried in a request header, never a URL path/query parameter - a
 * typical access log captures the request line (method + path + query string) but not headers, so
 * this keeps the raw secret out of that log surface. No caching anywhere in the chain: the response is
 * live location data that must never be served stale from a shared proxy/browser cache.
 */
@RestController
public class TripShareLookupController {

    private final TripShareService tripShareService;
    private final ClientIpResolver clientIpResolver;

    public TripShareLookupController(TripShareService tripShareService, ClientIpResolver clientIpResolver) {
        this.tripShareService = tripShareService;
        this.clientIpResolver = clientIpResolver;
    }

    /** docs/api/booking.md's own precedent (see {@code BookingController}'s idempotency-key comment):
     *  a jakarta-validation annotation directly on a {@code @RequestHeader} parameter doesn't map to a
     *  clean error through this app's {@code GlobalExceptionHandler} - checked manually instead, same
     *  {@link ResourceNotFoundException} every other failure path here throws (identical error for a
     *  missing header as for an unknown/expired/revoked token - no extra signal either way). */
    @GetMapping("/api/v1/trip-shares/lookup")
    public ResponseEntity<ApiResponse<TripShareLookupResponse>> lookup(
            @RequestHeader(value = "X-Trip-Share-Token", required = false) String token, HttpServletRequest request) {
        if (token == null || token.isBlank()) {
            throw new ResourceNotFoundException();
        }
        TripShareLookupResponse response = tripShareService.resolve(token, clientIpResolver.resolve(request));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(response));
    }
}
