package com.ladygo.locations.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.locations.domain.City;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CityRepository extends JpaRepository<City, UUID> {

    List<City> findByActiveTrue();

    Optional<City> findByNameAndCountry(String name, String country);

    /** docs/api/booking.md section 1: serving city resolved from the pickup point, not a default. */
    @Query(value = """
            SELECT * FROM cities
            WHERE active
              AND ST_Contains(service_area::geometry, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326))
            LIMIT 1
            """, nativeQuery = true)
    Optional<City> findServingCity(@Param("lon") double lon, @Param("lat") double lat);
}
