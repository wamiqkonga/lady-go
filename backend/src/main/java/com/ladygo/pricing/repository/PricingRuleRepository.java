package com.ladygo.pricing.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.pricing.domain.PricingRule;
import com.ladygo.vehicles.domain.VehicleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PricingRuleRepository extends JpaRepository<PricingRule, UUID> {

    /** The single rule in force for a city and vehicle type at {@code at} (the database forbids overlaps). */
    @Query("""
            SELECT r FROM PricingRule r
            WHERE r.cityId = :cityId AND r.vehicleType = :vehicleType
              AND r.effectiveFrom <= :at AND (r.effectiveTo IS NULL OR r.effectiveTo > :at)
            """)
    Optional<PricingRule> findActive(@Param("cityId") UUID cityId, @Param("vehicleType") VehicleType vehicleType,
                                     @Param("at") Instant at);
}
