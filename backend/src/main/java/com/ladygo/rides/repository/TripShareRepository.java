package com.ladygo.rides.repository;

import java.util.Optional;
import java.util.UUID;
import com.ladygo.rides.domain.TripShare;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripShareRepository extends JpaRepository<TripShare, UUID> {

    Optional<TripShare> findByTokenHash(String tokenHash);
}
