package com.ladygo.payments.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.payments.domain.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    List<PaymentTransaction> findByBookingId(UUID bookingId);
}
