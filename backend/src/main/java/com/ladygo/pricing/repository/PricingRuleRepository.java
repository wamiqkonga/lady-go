package com.ladygo.pricing.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.pricing.domain.PricingRule;
import com.ladygo.vehicles.domain.VehicleType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    /** Every version for a city and vehicle type, newest first (admin Fares history). */
    List<PricingRule> findByCityIdAndVehicleTypeOrderByEffectiveFromDesc(UUID cityId, VehicleType vehicleType);

    /** The version that starts last, locked for update. Pass {@code Pageable.ofSize(1)}; the caller already holds the city lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM PricingRule r
            WHERE r.cityId = :cityId AND r.vehicleType = :vehicleType
            ORDER BY r.effectiveFrom DESC
            """)
    List<PricingRule> lockLatest(@Param("cityId") UUID cityId, @Param("vehicleType") VehicleType vehicleType,
                                 Pageable limit);

    /** The version whose period ends exactly at {@code endsAt} (the one that precedes a scheduled change). */
    Optional<PricingRule> findByCityIdAndVehicleTypeAndEffectiveTo(UUID cityId, VehicleType vehicleType, Instant endsAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PricingRule r WHERE r.id = :id")
    Optional<PricingRule> findByIdForUpdate(@Param("id") UUID id);

    /** City and vehicle type pairs that already have at least one fare (admin Fares city list). */
    @Query("SELECT DISTINCT r.cityId, r.vehicleType FROM PricingRule r")
    List<Object[]> findCityVehicleTypePairs();

    /** The only UPDATE the Fares feature performs: close an open version at the instant its successor opens. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PricingRule r SET r.effectiveTo = :endsAt WHERE r.id = :id AND r.effectiveTo IS NULL")
    int closeOpenRule(@Param("id") UUID id, @Param("endsAt") Instant endsAt);

    /** Undo of {@link #closeOpenRule} when the scheduled successor is cancelled; matches only the exact closing instant. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PricingRule r SET r.effectiveTo = NULL WHERE r.id = :id AND r.effectiveTo = :endsAt")
    int reopenRule(@Param("id") UUID id, @Param("endsAt") Instant endsAt);

    /** The only DELETE: one version by its own id, and only while it has not yet taken effect. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PricingRule r WHERE r.id = :id AND r.effectiveFrom > :now")
    int deleteFutureRule(@Param("id") UUID id, @Param("now") Instant now);
}
