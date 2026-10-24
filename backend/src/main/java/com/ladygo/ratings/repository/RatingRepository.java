package com.ladygo.ratings.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.ratings.domain.RaterRole;
import com.ladygo.ratings.domain.Rating;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RatingRepository extends JpaRepository<Rating, UUID> {

    boolean existsByRideIdAndRaterId(UUID rideId, UUID raterId);

    Optional<Rating> findByRideIdAndRaterId(UUID rideId, UUID raterId);

    List<Rating> findByRateeIdOrderByCreatedAtDesc(UUID rateeId);

    /** Most recent ratings received by a user from the given side, newest first (pass a page of N for "last N"). */
    List<Rating> findByRateeIdAndRaterRoleOrderByCreatedAtDesc(UUID rateeId, RaterRole raterRole, Pageable pageable);

    long countByRateeIdAndRaterRole(UUID rateeId, RaterRole raterRole);

    /** Sum of scores; with the count this gives an exact (integer) average, no floating point. */
    @Query("select coalesce(sum(r.score), 0) from Rating r where r.rateeId = :rateeId and r.raterRole = :raterRole")
    long sumScores(@Param("rateeId") UUID rateeId, @Param("raterRole") RaterRole raterRole);

    long countByRateeIdAndRaterRoleAndScore(UUID rateeId, RaterRole raterRole, short score);

    /** Repeat-offender check: how many ratings of this score one rater gave one ratee since a point in time. */
    long countByRaterIdAndRateeIdAndScoreAndCreatedAtAfter(UUID raterId, UUID rateeId, short score, Instant after);
}
