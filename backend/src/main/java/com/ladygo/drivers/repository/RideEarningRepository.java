package com.ladygo.drivers.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.drivers.domain.RideEarning;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RideEarningRepository extends JpaRepository<RideEarning, UUID> {

    Optional<RideEarning> findByRideId(UUID rideId);

    /**
     * Writes the earnings row for a ride exactly once. {@code ON CONFLICT DO NOTHING} (not catch-and-ignore
     * of a constraint violation) because a failed INSERT would abort the surrounding Postgres transaction -
     * and this runs inside the ride-completion transaction, which a replayed event must never break.
     * Returns 1 when inserted, 0 when a row for this ride already existed.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO ride_earnings (ride_id, driver_id, gross, commission_bps, commission, net,
                                       currency, payment_method, earned_at)
            VALUES (:rideId, :driverId, :gross, :bps, :commission, :net, :currency, :paymentMethod, :earnedAt)
            ON CONFLICT (ride_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("rideId") UUID rideId, @Param("driverId") UUID driverId,
                       @Param("gross") long gross, @Param("bps") int bps, @Param("commission") long commission,
                       @Param("net") long net, @Param("currency") String currency,
                       @Param("paymentMethod") String paymentMethod, @Param("earnedAt") Instant earnedAt);
}
