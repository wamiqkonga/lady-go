package com.ladygo.admin.web;

import java.util.UUID;
import com.ladygo.admin.service.AdminSafetyService;
import com.ladygo.admin.web.dto.SafetyIncidentDetailResponse;
import com.ladygo.admin.web.dto.SafetyIncidentNoteRequest;
import com.ladygo.admin.web.dto.SafetyIncidentResolveRequest;
import com.ladygo.admin.web.dto.SafetyIncidentSummaryResponse;
import com.ladygo.admin.web.dto.SafetyOpenCountResponse;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.common.api.PageResponse;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.security.net.ClientIpResolver;
import com.ladygo.support.domain.IncidentStatus;
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

/** The safety queue. SUPPORT and above; a rider or captain token never reaches it. No step-up on purpose (see the service). */
@RestController
@RequestMapping("/api/v1/admin/safety/incidents")
@PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN', 'SUPER_ADMIN')")
public class AdminSafetyController {

    private final AdminSafetyService service;
    private final ClientIpResolver clientIpResolver;

    public AdminSafetyController(AdminSafetyService service, ClientIpResolver clientIpResolver) {
        this.service = service;
        this.clientIpResolver = clientIpResolver;
    }

    /** Unresolved incidents by default; pass {@code status=RESOLVED} for the history. */
    @GetMapping
    public ApiResponse<PageResponse<SafetyIncidentSummaryResponse>> list(
            @RequestParam(required = false) IncidentStatus status, Pageable pageable) {
        return ApiResponse.ok(service.list(status, pageable));
    }

    @GetMapping("/open-count")
    public ApiResponse<SafetyOpenCountResponse> openCount() {
        return ApiResponse.ok(service.openCount());
    }

    @GetMapping("/{id}")
    public ApiResponse<SafetyIncidentDetailResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/{id}/acknowledge")
    public ApiResponse<SafetyIncidentDetailResponse> acknowledge(
            @PathVariable UUID id, @AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        return ApiResponse.ok(service.acknowledge(id, principal.userId(), principal.role().name(),
                clientIpResolver.resolve(request)));
    }

    @PostMapping("/{id}/notes")
    public ApiResponse<SafetyIncidentDetailResponse> addNote(
            @PathVariable UUID id, @Valid @RequestBody SafetyIncidentNoteRequest body,
            @AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        return ApiResponse.ok(service.addNote(id, body.note(), principal.userId(), principal.role().name(),
                clientIpResolver.resolve(request)));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<SafetyIncidentDetailResponse> resolve(
            @PathVariable UUID id, @Valid @RequestBody SafetyIncidentResolveRequest body,
            @AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        return ApiResponse.ok(service.resolve(id, body.outcome(), body.note(), principal.userId(),
                principal.role().name(), clientIpResolver.resolve(request)));
    }
}
