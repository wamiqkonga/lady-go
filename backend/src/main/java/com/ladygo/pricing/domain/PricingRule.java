package com.ladygo.pricing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import com.ladygo.common.persistence.CreatedEntity;
import com.ladygo.vehicles.domain.VehicleType;
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
@Table(name = "pricing_rules")
@Getter
@Setter
public class PricingRule extends CreatedEntity {

    @Column(name = "city_id", nullable = false)
    private UUID cityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", length = 20, nullable = false)
    private VehicleType vehicleType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "base_fare", nullable = false)
    private long baseFare;

    @Column(name = "per_km", nullable = false)
    private long perKm;

    @Column(name = "per_minute", nullable = false)
    private long perMinute;

    @Column(name = "minimum_fare", nullable = false)
    private long minimumFare;

    @Column(name = "cancellation_fee", nullable = false)
    private long cancellationFee = 0L;

    @Column(name = "service_fee_pct", precision = 5, scale = 2, nullable = false)
    private BigDecimal serviceFeePct = BigDecimal.ZERO;

    @Column(name = "service_fee_flat", nullable = false)
    private long serviceFeeFlat = 0L;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "surge_config", columnDefinition = "jsonb")
    private Map<String, Object> surgeConfig;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_by")
    private UUID createdBy;
}
