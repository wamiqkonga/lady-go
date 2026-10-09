package com.ladygo.rides.domain;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.common.domain.ActorType;
import com.ladygo.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The one durable record behind every "admin should look at this" operational signal this phase
 * introduces (docs/api/ride-lifecycle.md section 4) - deliberately not the verification module's
 * {@code EligibilityReport}, which models peer-reporting a person for an eligibility concern, a
 * different thing from an operational/billing signal like an early end or a cash dispute. Mutable
 * (AuditedEntity, not CreatedEntity) because {@code riderResponse}/{@code riderRespondedAt} are set
 * after creation, when the rider acknowledges or disputes a flag that prompted them.
 */
@Entity
@Table(name = "ride_flags")
@Getter
@Setter
public class RideFlag extends AuditedEntity {

    /** Nullable - a {@code RIDER_SAFETY_CANCEL} can be raised before any {@code rides} row exists yet
     *  (before DRIVER_ACCEPTED); every other flag type always has one. */
    @Column(name = "ride_id")
    private UUID rideId;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 40, nullable = false)
    private RideFlagType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "raised_by", length = 10, nullable = false)
    private ActorType raisedBy;

    @Column(name = "distance_from_expected_m")
    private Integer distanceFromExpectedM;

    @Column(name = "cap_exceeded", nullable = false)
    private boolean capExceeded = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "rider_response", length = 15)
    private RideFlagRiderResponse riderResponse;

    @Column(name = "rider_responded_at")
    private Instant riderRespondedAt;

    /** Admin triage state - separate from {@link #riderResponse}, which is the rider's own
     *  acknowledge/dispute of a flag raised about them. This is the admin-web oversight screen's own
     *  "has anyone actually looked at this" marker (docs/product/admin-web-design.md section 4). */
    @Column(name = "admin_reviewed_by")
    private UUID adminReviewedBy;

    @Column(name = "admin_reviewed_at")
    private Instant adminReviewedAt;
}
