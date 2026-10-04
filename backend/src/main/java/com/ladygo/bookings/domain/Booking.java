package com.ladygo.bookings.domain;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.common.domain.ActorType;
import com.ladygo.common.persistence.AuditedEntity;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.vehicles.domain.VehicleType;
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
@Table(name = "bookings")
@Getter
@Setter
public class Booking extends AuditedEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "driver_id")
    private UUID driverId;

    @Column(name = "city_id", nullable = false)
    private UUID cityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", length = 20, nullable = false)
    private VehicleType vehicleType;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "pickup_location", columnDefinition = "geography(Point,4326)", nullable = false)
    private Point pickupLocation;

    @Column(name = "pickup_address", columnDefinition = "text", nullable = false)
    private String pickupAddress;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "destination_location", columnDefinition = "geography(Point,4326)", nullable = false)
    private Point destinationLocation;

    @Column(name = "destination_address", columnDefinition = "text", nullable = false)
    private String destinationAddress;

    @Column(name = "estimated_distance_m")
    private Integer estimatedDistanceM;

    @Column(name = "estimated_duration_s")
    private Integer estimatedDurationS;

    @Column(name = "estimated_fare")
    private Long estimatedFare;

    @Column(name = "final_fare")
    private Long finalFare;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "pricing_rule_id")
    private UUID pricingRuleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 10, nullable = false)
    private PaymentMode paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private BookingStatus status = BookingStatus.REQUESTED;

    @Column(name = "cancel_reason", columnDefinition = "text")
    private String cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by", length = 20)
    private ActorType cancelledBy;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "ride_pin_hash", length = 255)
    private String ridePinHash;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
