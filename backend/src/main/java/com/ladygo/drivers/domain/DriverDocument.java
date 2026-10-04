package com.ladygo.drivers.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import com.ladygo.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "driver_documents")
@Getter
@Setter
public class DriverDocument extends AuditedEntity {

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 30, nullable = false)
    private DocumentType type;

    @Column(name = "storage_key", length = 500, nullable = false)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private DocumentStatus status = DocumentStatus.PENDING;

    @Column(name = "reviewer_id")
    private UUID reviewerId;

    @Column(name = "review_note", columnDefinition = "text")
    private String reviewNote;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "expires_at")
    private LocalDate expiresAt;
}
