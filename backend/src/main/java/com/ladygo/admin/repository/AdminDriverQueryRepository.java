package com.ladygo.admin.repository;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.admin.web.dto.AdminDriverDetailResponse.AdminDriverEarningsTotals;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Admin-only reads and the suspend/activate writes for the Drivers screen. Every write is a bounded
 *  UPDATE by id with a status guard, so a stale click can never move a driver out of the wrong state. */
public interface AdminDriverQueryRepository extends Repository<Driver, UUID> {

    /**
     * Name / phone fragment (LIKE, {@code \} escape, caller passes a lower-cased escaped pattern) or exact id,
     * optionally narrowed to one verification status. {@code filterByStatus} is explicit because a null enum
     * parameter has no usable type in a Postgres comparison.
     */
    @Query(value = """
            SELECT new com.ladygo.admin.repository.AdminDriverRow(d.id, u.name, u.phone, d.verificationStatus,
                   d.availabilityStatus, d.ratingAvg, d.totalRides, d.createdAt)
            FROM Driver d JOIN User u ON u.id = d.userId
            WHERE (:filterByStatus = false OR d.verificationStatus = :status)
              AND (:matchAll = true OR d.id = :idMatch
                   OR lower(coalesce(u.name, '')) LIKE :pattern ESCAPE '\\'
                   OR u.phone LIKE :pattern ESCAPE '\\')
            """,
            countQuery = """
            SELECT count(d) FROM Driver d JOIN User u ON u.id = d.userId
            WHERE (:filterByStatus = false OR d.verificationStatus = :status)
              AND (:matchAll = true OR d.id = :idMatch
                   OR lower(coalesce(u.name, '')) LIKE :pattern ESCAPE '\\'
                   OR u.phone LIKE :pattern ESCAPE '\\')
            """)
    Page<AdminDriverRow> search(@Param("matchAll") boolean matchAll, @Param("idMatch") UUID idMatch,
            @Param("pattern") String pattern, @Param("filterByStatus") boolean filterByStatus,
            @Param("status") DriverVerificationStatus status, Pageable pageable);

    @Query("""
            SELECT new com.ladygo.admin.web.dto.AdminDriverDetailResponse$AdminDriverEarningsTotals(
                   count(e), coalesce(sum(e.gross), 0), coalesce(sum(e.commission), 0), coalesce(sum(e.net), 0))
            FROM RideEarning e WHERE e.driverId = :driverId
            """)
    AdminDriverEarningsTotals earningsTotals(@Param("driverId") UUID driverId);

    /** APPROVED -> SUSPENDED and offline, in one statement; 0 rows when the driver is not currently approved. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Driver d SET d.verificationStatus = com.ladygo.drivers.domain.DriverVerificationStatus.SUSPENDED,
                   d.availabilityStatus = com.ladygo.drivers.domain.DriverAvailability.SUSPENDED,
                   d.version = d.version + 1, d.updatedAt = :now
            WHERE d.id = :id AND d.verificationStatus = com.ladygo.drivers.domain.DriverVerificationStatus.APPROVED
            """)
    int suspend(@Param("id") UUID id, @Param("now") Instant now);

    /** SUSPENDED -> APPROVED but OFFLINE: the driver chooses when to go online again. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Driver d SET d.verificationStatus = com.ladygo.drivers.domain.DriverVerificationStatus.APPROVED,
                   d.availabilityStatus = com.ladygo.drivers.domain.DriverAvailability.OFFLINE,
                   d.version = d.version + 1, d.updatedAt = :now
            WHERE d.id = :id AND d.verificationStatus = com.ladygo.drivers.domain.DriverVerificationStatus.SUSPENDED
            """)
    int activate(@Param("id") UUID id, @Param("now") Instant now);
}
