package com.ladygo.verification.domain;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Always scoped to a shared booking - "only between users who shared a booking" is a schema-level
 * guarantee (the bookingId FK), not just an API-layer check (docs/api/driver-onboarding.md section 5).
 * Never exposes {@code reporterId} to the {@code subjectId} side - anonymous to the reported person.
 */
@Entity
@Table(name = "eligibility_reports")
@Getter
@Setter
public class EligibilityReport extends AuditedEntity {

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", length = 10, nullable = false)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "reason", columnDefinition = "text", nullable = false)
    private String reason;

    @Column(name = "auto_created", nullable = false)
    private boolean autoCreated = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 15, nullable = false)
    private EligibilityReportStatus status = EligibilityReportStatus.OPEN;

    @Column(name = "reviewer_id")
    private UUID reviewerId;

    @Column(name = "review_note", columnDefinition = "text")
    private String reviewNote;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;
}
