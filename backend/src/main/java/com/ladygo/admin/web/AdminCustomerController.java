package com.ladygo.admin.web;

import java.util.UUID;
import com.ladygo.admin.service.AdminCustomerService;
import com.ladygo.admin.web.dto.AdminCustomerActionRequest;
import com.ladygo.admin.web.dto.AdminCustomerDetailResponse;
import com.ladygo.admin.web.dto.AdminCustomerSummaryResponse;
import com.ladygo.auth.service.StepUpGuard;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.common.api.PageResponse;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.security.net.ClientIpResolver;
import com.ladygo.users.domain.UserStatus;
import io.swagger.v3.oas.annotations.Operation;
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

/** Admin customers screen. Reads: SUPPORT and above. Suspend/activate: ADMIN and above, fresh step-up,
 *  written reason, audited. */
@RestController
@RequestMapping("/api/v1/admin/customers")
public class AdminCustomerController {

    private final AdminCustomerService service;
    private final StepUpGuard stepUpGuard;
    private final ClientIpResolver clientIpResolver;

    public AdminCustomerController(
            AdminCustomerService service, StepUpGuard stepUpGuard, ClientIpResolver clientIpResolver) {
        this.service = service;
        this.stepUpGuard = stepUpGuard;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping
    @Operation(operationId = "listCustomers")
    @PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN', 'SUPER_ADMIN')")
    public ApiResponse<PageResponse<AdminCustomerSummaryResponse>> list(
            @RequestParam(required = false) String q, @RequestParam(required = false) UserStatus status,
            Pageable pageable) {
        return ApiResponse.ok(service.list(q, status, pageable));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getCustomer")
    @PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN', 'SUPER_ADMIN')")
    public ApiResponse<AdminCustomerDetailResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/{id}/suspend")
    @Operation(operationId = "suspendCustomer")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ApiResponse<AdminCustomerDetailResponse> suspend(
            @PathVariable UUID id, @Valid @RequestBody AdminCustomerActionRequest body,
            @AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        stepUpGuard.require(principal);
        return ApiResponse.ok(service.suspend(
                id, principal.userId(), principal.role(), body.reason(), clientIpResolver.resolve(request)));
    }

    @PostMapping("/{id}/activate")
    @Operation(operationId = "activateCustomer")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ApiResponse<AdminCustomerDetailResponse> activate(
            @PathVariable UUID id, @Valid @RequestBody AdminCustomerActionRequest body,
            @AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        stepUpGuard.require(principal);
        return ApiResponse.ok(service.activate(
                id, principal.userId(), principal.role(), body.reason(), clientIpResolver.resolve(request)));
    }
}
