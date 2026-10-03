package com.ladygo.payments.repository;

import java.util.List;
import java.util.UUID;
import com.ladygo.payments.domain.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, UUID> {

    List<PaymentMethod> findByCustomerId(UUID customerId);
}
