package com.ladygo.payments.repository;

import java.util.List;
import java.util.UUID;
import com.ladygo.payments.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findByTransactionId(UUID transactionId);
}
