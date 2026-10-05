package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.verification.domain.EligibilityReportStatus;
import com.ladygo.verification.domain.SubjectType;

/** Admin-only view - never exposed to the reported person (anonymous to them, docs/api/driver-onboarding.md section 5). */
public record EligibilityReportAdminResponse(
        UUID id, UUID bookingId, UUID reporterId, SubjectType subjectType, UUID subjectId, String reason,
        boolean autoCreated, EligibilityReportStatus status, Instant createdAt) {
}
