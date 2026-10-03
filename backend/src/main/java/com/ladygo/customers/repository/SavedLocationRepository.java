package com.ladygo.customers.repository;

import java.util.List;
import java.util.UUID;
import com.ladygo.customers.domain.SavedLocation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedLocationRepository extends JpaRepository<SavedLocation, UUID> {

    List<SavedLocation> findByCustomerIdOrderByLabelAsc(UUID customerId);

    long countByCustomerId(UUID customerId);
}
