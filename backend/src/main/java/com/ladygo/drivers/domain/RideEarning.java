package com.ladygo.drivers.domain;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.common.persistence.CreatedEntity;
import com.ladygo.payments.domain.PaymentMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Immutable earnings snapshot for one completed ride (docs/api/driver-earnings-ratings.md section 5).
 * Rows are written by {@code RideEarningRepository.insertIfAbsent} (insert guarded by the unique ride
 * id); this mapping exists for reads and tests. Money is in minor units (ADR-007).
 */
@Entity
@Table(name = "ride_earnings")
@Getter
@Setter
public class RideEarning extends CreatedEntity {

    @Column(name = "ride_id", nullable = false)
    private UUID rideId;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "gross", nullable = false)
    private long gross;

    @Column(name = "commission_bps", nullable = false)
    private int commissionBps;

    @Column(name = "commission", nullable = false)
    private long commission;

    @Column(name = "net", nullable = false)
    private long net;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 10, nullable = false)
    private PaymentMode paymentMethod;

    @Column(name = "earned_at", nullable = false)
    private Instant earnedAt;

    @Column(name = "payout_batch_id")
    private UUID payoutBatchId;

    @Column(name = "settled_at")
    private Instant settledAt;
}
