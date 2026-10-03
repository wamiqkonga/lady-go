package com.ladygo.support.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "safety_incidents")
@Getter
@Setter
public class SafetyIncident extends AuditedEntity {

    @Column(name = "ride_id", nullable = false)
    private UUID rideId;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20, nullable = false)
    private IncidentType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 10, nullable = false)
    private IncidentSeverity severity = IncidentSeverity.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 15, nullable = false)
    private IncidentStatus status = IncidentStatus.OPEN;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "location", columnDefinition = "geography(Point,4326)")
    private Point location;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "acknowledged_by")
    private UUID acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "legal_hold", nullable = false)
    private boolean legalHold = false;
}
