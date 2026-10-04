package com.ladygo.drivers.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DriverRepository extends JpaRepository<Driver, UUID> {

    Optional<Driver> findByUserId(UUID userId);

    List<Driver> findByVerificationStatus(DriverVerificationStatus status);

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
}
