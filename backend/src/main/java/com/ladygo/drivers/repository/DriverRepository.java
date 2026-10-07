package com.ladygo.drivers.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DriverRepository extends JpaRepository<Driver, UUID> {

    Optional<Driver> findByUserId(UUID userId);

    List<Driver> findByVerificationStatus(DriverVerificationStatus status);

    Page<Driver> findByVerificationStatusOrderByCreatedAtAsc(DriverVerificationStatus status, Pageable pageable);

    /**
     * Online drivers within {@code radiusM} metres of a point, nearest first (PostGIS geography, metres).
     * Matching (Phase 6) will use the Redis GEO index for live search; this query is the database source of
     * truth and the fallback if Redis is unavailable.
     */
    @Query(value = """
            SELECT d.* FROM drivers d
            WHERE d.city_id = :cityId
              AND d.availability_status = 'ONLINE'
              AND d.current_location IS NOT NULL
              AND ST_DWithin(d.current_location,
                             CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography), :radiusM)
            ORDER BY ST_Distance(d.current_location,
                                 CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography))
            LIMIT :maxResults
            """, nativeQuery = true)
    List<Driver> findNearbyOnline(@Param("cityId") UUID cityId, @Param("lon") double lon, @Param("lat") double lat,
                                  @Param("radiusM") double radiusM, @Param("maxResults") int maxResults);

    /** Atomic claim for an offer - 0 rows affected means another booking's search already claimed this
     *  driver (or they went offline), so the caller must move on to the next candidate, same
     *  check-via-row-count idiom as the ride-selfie claim. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Driver d SET d.availabilityStatus = com.ladygo.drivers.domain.DriverAvailability.BUSY "
            + "WHERE d.id = :id AND d.availabilityStatus = com.ladygo.drivers.domain.DriverAvailability.ONLINE")
    int claimForOffer(@Param("id") UUID id);

    /** Releases a driver back to ONLINE after an offer is rejected/expired - a no-op (0 rows) if the
     *  driver is no longer BUSY (e.g. went offline in the meantime). */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Driver d SET d.availabilityStatus = com.ladygo.drivers.domain.DriverAvailability.ONLINE "
            + "WHERE d.id = :id AND d.availabilityStatus = com.ladygo.drivers.domain.DriverAvailability.BUSY")
    int releaseFromOffer(@Param("id") UUID id);
}
