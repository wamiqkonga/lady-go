package com.ladygo.customers.repository;

import java.util.List;
import java.util.UUID;
import com.ladygo.customers.domain.EmergencyContact;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmergencyContactRepository extends JpaRepository<EmergencyContact, UUID> {

    List<EmergencyContact> findByCustomerId(UUID customerId);

    long countByCustomerId(UUID customerId);
}
