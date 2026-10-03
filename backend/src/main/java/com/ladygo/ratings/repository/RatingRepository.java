package com.ladygo.ratings.repository;

import java.util.List;
import java.util.UUID;
import com.ladygo.ratings.domain.Rating;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RatingRepository extends JpaRepository<Rating, UUID> {

    boolean existsByRideIdAndRaterId(UUID rideId, UUID raterId);

    List<Rating> findByRateeIdOrderByCreatedAtDesc(UUID rateeId);
}
