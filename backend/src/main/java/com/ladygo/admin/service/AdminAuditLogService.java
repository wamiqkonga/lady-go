package com.ladygo.admin.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.admin.web.dto.AdminAuditLogResponse;
import com.ladygo.common.api.PageResponse;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/** docs/product/admin-web-design.md section 5 - read-only, the table already has every field needed. */
@Service
public class AdminAuditLogService {

    private final AuditLogRepository auditLogs;

    public AdminAuditLogService(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    public PageResponse<AdminAuditLogResponse> list(
            UUID actorId, String action, String targetType, String targetId,
            Instant createdAfter, Instant createdBefore, Pageable pageable) {
        Specification<AuditLog> spec = specification(actorId, action, targetType, targetId, createdAfter, createdBefore);
        // An unsorted query has no guaranteed row order (CI caught this as a flaky test) - default to
        // newest-first, the only sensible reading order for a log, when the caller didn't ask for one.
        Pageable sorted = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSortOr(Sort.by(Sort.Direction.DESC, "createdAt")));
        Page<AuditLog> page = auditLogs.findAll(spec, sorted);
        return PageResponse.of(page.map(AdminAuditLogService::toResponse));
    }

    private static AdminAuditLogResponse toResponse(AuditLog entry) {
        return new AdminAuditLogResponse(
                entry.getId(), entry.getActorId(), entry.getActorRole(), entry.getAction(), entry.getTargetType(),
                entry.getTargetId(), entry.getMetadata(), entry.getIp(), entry.getCreatedAt());
    }

    private static Specification<AuditLog> specification(
            UUID actorId, String action, String targetType, String targetId,
            Instant createdAfter, Instant createdBefore) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actorId != null) {
                predicates.add(cb.equal(root.get("actorId"), actorId));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (targetType != null && !targetType.isBlank()) {
                predicates.add(cb.equal(root.get("targetType"), targetType));
            }
            if (targetId != null && !targetId.isBlank()) {
                predicates.add(cb.equal(root.get("targetId"), targetId));
            }
            if (createdAfter != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), createdAfter));
            }
            if (createdBefore != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), createdBefore));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
