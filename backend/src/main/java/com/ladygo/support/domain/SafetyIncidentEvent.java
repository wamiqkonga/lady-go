package com.ladygo.support.domain;

import java.util.Map;
import java.util.UUID;
import com.ladygo.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "safety_incident_events")
@Getter
@Setter
public class SafetyIncidentEvent extends CreatedEntity {

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "event_type", length = 30, nullable = false)
    private String eventType;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;
}
