package com.ladygo.admin.web;

import java.util.List;
import java.util.UUID;
import com.ladygo.admin.service.AdminPricingRuleService;
import com.ladygo.admin.web.dto.PricingCityResponse;
import com.ladygo.admin.web.dto.PricingPreviewResponse;
import com.ladygo.admin.web.dto.PricingRuleCancelRequest;
import com.ladygo.admin.web.dto.PricingRuleCreateRequest;
import com.ladygo.admin.web.dto.PricingRulePreviewRequest;
import com.ladygo.admin.web.dto.PricingRuleResponse;
import com.ladygo.admin.web.dto.PricingRulesViewResponse;
import com.ladygo.auth.service.StepUpGuard;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.security.net.ClientIpResolver;
import com.ladygo.vehicles.domain.VehicleType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fares (pricing rules) admin API, docs/development/plans/2026-11-03-admin-pricing-rules.md section 4d. SUPER_ADMIN
 * only, for reads as well as writes (a fare is commercially sensitive); both writes need a fresh step-up code.
 */
@RestController
@RequestMapping("/api/v1/admin/pricing-rules")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminPricingRuleController {

    private final AdminPricingRuleService service;
    private final ClientIpResolver clientIpResolver;
    private final StepUpGuard stepUpGuard;

    public AdminPricingRuleController(
            AdminPricingRuleService service, ClientIpResolver clientIpResolver, StepUpGuard stepUpGuard) {
        this.service = service;
        this.clientIpResolver = clientIpResolver;
        this.stepUpGuard = stepUpGuard;
    }

    @GetMapping
    public ApiResponse<PricingRulesViewResponse> view(
            @RequestParam UUID cityId, @RequestParam VehicleType vehicleType) {
        return ApiResponse.ok(service.view(cityId, vehicleType));
    }

    @GetMapping("/cities")
    public ApiResponse<List<PricingCityResponse>> cities() {
        return ApiResponse.ok(service.cities());
    }

    @PostMapping("/preview")
    public ApiResponse<PricingPreviewResponse> preview(@Valid @RequestBody PricingRulePreviewRequest request) {
        return ApiResponse.ok(service.preview(request));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PricingRuleResponse> create(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody PricingRuleCreateRequest request,
            HttpServletRequest httpRequest) {
        stepUpGuard.require(principal);
        return ApiResponse.ok(service.create(
                request, principal.userId(), principal.role().name(), clientIpResolver.resolve(httpRequest)));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<PricingRuleResponse> cancel(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody PricingRuleCancelRequest request, HttpServletRequest httpRequest) {
        stepUpGuard.require(principal);
        return ApiResponse.ok(service.cancel(
                id, request.reason(), principal.userId(), principal.role().name(), clientIpResolver.resolve(httpRequest)));
    }
}
