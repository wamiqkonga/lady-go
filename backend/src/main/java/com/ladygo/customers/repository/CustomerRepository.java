package com.ladygo.customers.repository;

import java.util.Optional;
import java.util.UUID;
import com.ladygo.customers.domain.Customer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByUserId(UUID userId);

    /**
     * Serializes concurrent count-then-insert calls for the same customer (saved locations, emergency
     * contacts) so two simultaneous requests can't both pass the limit check before either commits
     * (adversarial review finding, MEDIUM - a plain count-check has no such guarantee under READ
     * COMMITTED). Scoped to one customer's row; unrelated customers are unaffected.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> lockById(@Param("id") UUID id);
}
