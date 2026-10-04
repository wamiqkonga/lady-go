package com.ladygo.rides.domain;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "rides")
@Getter
@Setter
public class Ride extends AuditedEntity {

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private RideStatus status = RideStatus.ASSIGNED;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "start_location", columnDefinition = "geography(Point,4326)")
    private Point startLocation;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "end_location", columnDefinition = "geography(Point,4326)")
    private Point endLocation;

    @Column(name = "actual_distance_m")
    private Integer actualDistanceM;

    @Column(name = "actual_duration_s")
    private Integer actualDurationS;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
