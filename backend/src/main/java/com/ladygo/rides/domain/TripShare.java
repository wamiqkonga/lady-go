package com.ladygo.rides.domain;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "trip_shares")
@Getter
@Setter
public class TripShare extends CreatedEntity {

    @Column(name = "ride_id", nullable = false)
    private UUID rideId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
