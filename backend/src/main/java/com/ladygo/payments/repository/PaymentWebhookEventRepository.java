package com.ladygo.payments.repository;

import java.util.UUID;
import com.ladygo.payments.domain.PaymentWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, UUID> {

    boolean existsByProviderAndEventId(String provider, String eventId);
}
