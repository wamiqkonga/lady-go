package com.ladygo.bookings.domain;

import java.util.Map;
import java.util.UUID;
import com.ladygo.common.domain.ActorType;
import com.ladygo.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "booking_events")
@Getter
@Setter
public class BookingEvent extends CreatedEntity {

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private BookingStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 30, nullable = false)
    private BookingStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", length = 20, nullable = false)
    private ActorType actorType;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;
}
