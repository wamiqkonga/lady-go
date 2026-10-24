package com.ladygo.admin.web;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.admin.service.AdminAuditLogService;
import com.ladygo.admin.web.dto.AdminAuditLogResponse;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.common.api.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** docs/product/admin-web-design.md section 5 - read-only for every admin role. */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AdminAuditLogController {

    private final AdminAuditLogService service;

    public AdminAuditLogController(AdminAuditLogService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminAuditLogResponse>> list(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) Instant createdAfter,
            @RequestParam(required = false) Instant createdBefore,
            Pageable pageable) {
        return ApiResponse.ok(
                service.list(actorId, action, targetType, targetId, createdAfter, createdBefore, pageable));
    }
}
