package com.ladygo.bookings.domain;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "ride_offers")
@Getter
@Setter
public class RideOffer extends BaseEntity {

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OfferStatus status = OfferStatus.OFFERED;

    @Column(name = "distance_m")
    private Integer distanceM;

    @CreationTimestamp
    @Column(name = "offered_at", nullable = false, updatable = false)
    private Instant offeredAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "responded_at")
    private Instant respondedAt;
}
