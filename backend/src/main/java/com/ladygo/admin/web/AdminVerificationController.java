package com.ladygo.admin.web;

import java.net.URI;
import java.util.UUID;
import com.ladygo.admin.service.AdminVerificationService;
import com.ladygo.admin.web.dto.DocumentReviewRequest;
import com.ladygo.admin.web.dto.DriverDocumentAdminResponse;
import com.ladygo.admin.web.dto.DriverReviewSummaryResponse;
import com.ladygo.admin.web.dto.EligibilityReportAdminResponse;
import com.ladygo.admin.web.dto.EligibilityReportResolveRequest;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.common.api.PageResponse;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/api/driver-onboarding.md sections 3/5/6 - driver-documents-and-eligibility-reports review only.
 * There is no customer-verification admin queue.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AdminVerificationController {

    private final AdminVerificationService service;
    private final ClientIpResolver clientIpResolver;

    public AdminVerificationController(AdminVerificationService service, ClientIpResolver clientIpResolver) {
        this.service = service;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping("/driver-documents")
    public ApiResponse<PageResponse<DriverDocumentAdminResponse>> listPendingDocuments(Pageable pageable) {
        return ApiResponse.ok(service.listPendingDocuments(pageable));
    }

    /** A short-lived signed URL to view the document/selfie/vehicle-photo image itself, so an admin can
     *  actually see what they're approving or rejecting. Audited on every call. */
    @GetMapping("/driver-documents/{id}/view")
    public ApiResponse<URI> viewDocument(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id, HttpServletRequest httpRequest) {
        return ApiResponse.ok(service.viewDocumentUrl(
                id, principal.userId(), principal.role().name(), clientIpResolver.resolve(httpRequest)));
    }

    @PostMapping("/driver-documents/{id}/approve")
    public ApiResponse<DriverDocumentAdminResponse> approveDocument(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @RequestBody(required = false) DocumentReviewRequest request, HttpServletRequest httpRequest) {
        String note = request == null ? null : request.note();
        return ApiResponse.ok(service.approveDocument(
                id, principal.userId(), principal.role().name(), note, clientIpResolver.resolve(httpRequest)));
    }

    @PostMapping("/driver-documents/{id}/reject")
    public ApiResponse<DriverDocumentAdminResponse> rejectDocument(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @RequestBody DocumentReviewRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.ok(service.rejectDocument(
                id, principal.userId(), principal.role().name(), request.note(), clientIpResolver.resolve(httpRequest)));
    }

    @GetMapping("/eligibility-reports")
    public ApiResponse<PageResponse<EligibilityReportAdminResponse>> listOpenReports(Pageable pageable) {
        return ApiResponse.ok(service.listOpenReports(pageable));
    }

    @PostMapping("/eligibility-reports/{id}/resolve")
    public ApiResponse<EligibilityReportAdminResponse> resolveReport(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody EligibilityReportResolveRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.ok(service.resolveReport(id, request.status(), principal.userId(),
                principal.role().name(), request.note(), clientIpResolver.resolve(httpRequest)));
    }

    @PostMapping("/drivers/{id}/trigger-reverification")
    public ApiResponse<Void> triggerRandomReverification(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id, HttpServletRequest httpRequest) {
        service.triggerRandomReverification(id, principal.userId(), principal.role().name(),
                clientIpResolver.resolve(httpRequest));
        return ApiResponse.ok(null);
    }

    /** Everything needed to decide - the actual "admin approval path" (Phase 5 exit criteria). */
    @GetMapping("/drivers/{id}/review")
    public ApiResponse<DriverReviewSummaryResponse> getDriverReview(@PathVariable UUID id) {
        return ApiResponse.ok(service.getDriverReview(id));
    }

    @PostMapping("/drivers/{id}/approve")
    public ApiResponse<Void> approveDriver(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id, HttpServletRequest httpRequest) {
        service.approveDriver(id, principal.userId(), principal.role().name(), clientIpResolver.resolve(httpRequest));
        return ApiResponse.ok(null);
    }

    @PostMapping("/drivers/{id}/reject")
    public ApiResponse<Void> rejectDriver(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @RequestBody DocumentReviewRequest request, HttpServletRequest httpRequest) {
        service.rejectDriver(id, principal.userId(), principal.role().name(), request.note(),
                clientIpResolver.resolve(httpRequest));
        return ApiResponse.ok(null);
    }

    @PostMapping("/vehicles/{id}/approve")
    public ApiResponse<Void> approveVehicle(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id, HttpServletRequest httpRequest) {
        service.approveVehicle(id, principal.userId(), principal.role().name(), clientIpResolver.resolve(httpRequest));
        return ApiResponse.ok(null);
    }

    @PostMapping("/vehicles/{id}/reject")
    public ApiResponse<Void> rejectVehicle(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
            @RequestBody DocumentReviewRequest request, HttpServletRequest httpRequest) {
        service.rejectVehicle(id, principal.userId(), principal.role().name(), request.note(),
                clientIpResolver.resolve(httpRequest));
        return ApiResponse.ok(null);
    }
}
