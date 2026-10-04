package com.ladygo.customers.repository;

import java.util.Optional;
import java.util.UUID;
import com.ladygo.customers.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByUserId(UUID userId);
}
