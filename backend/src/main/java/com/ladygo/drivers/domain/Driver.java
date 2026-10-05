package com.ladygo.drivers.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
@Table(name = "drivers")
@Getter
@Setter
public class Driver extends AuditedEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "city_id", nullable = false)
    private UUID cityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", length = 20, nullable = false)
    private DriverVerificationStatus verificationStatus = DriverVerificationStatus.NOT_SUBMITTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "availability_status", length = 20, nullable = false)
    private DriverAvailability availabilityStatus = DriverAvailability.OFFLINE;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "current_location", columnDefinition = "geography(Point,4326)")
    private Point currentLocation;

    @Column(name = "current_location_at")
    private Instant currentLocationAt;

    @Column(name = "rating_avg", precision = 3, scale = 2, nullable = false)
    private BigDecimal ratingAvg = BigDecimal.ZERO;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount = 0;

    @Column(name = "total_rides", nullable = false)
    private int totalRides = 0;

    @Column(name = "license_number_enc", columnDefinition = "text")
    private String licenseNumberEnc;

    @Column(name = "license_expiry")
    private LocalDate licenseExpiry;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_verification_status", length = 20, nullable = false)
    private CheckStatus identityVerificationStatus = CheckStatus.NOT_SUBMITTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "background_verification_status", length = 20, nullable = false)
    private CheckStatus backgroundVerificationStatus = CheckStatus.NOT_SUBMITTED;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "reverification_requested_at")
    private Instant reverificationRequestedAt;

    @Column(name = "reverification_blocks_online_at")
    private Instant reverificationBlocksOnlineAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "reverification_reason", length = 30)
    private ReverificationReason reverificationReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
