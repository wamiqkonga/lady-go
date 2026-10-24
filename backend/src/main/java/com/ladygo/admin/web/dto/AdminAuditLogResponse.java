package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** docs/product/admin-flows.md section 11 / admin-web-design.md section 5. */
public record AdminAuditLogResponse(
        UUID id,
        UUID actorId,
        String actorRole,
        String action,
        String targetType,
        String targetId,
        Map<String, Object> metadata,
        String ip,
        Instant createdAt) {
}
