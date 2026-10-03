package com.ladygo.payments.domain;

import java.time.Instant;
import java.util.Map;
import com.ladygo.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payment_webhook_events")
@Getter
@Setter
public class PaymentWebhookEvent extends BaseEntity {

    @Column(name = "provider", length = 50, nullable = false)
    private String provider;

    @Column(name = "event_id", length = 150, nullable = false)
    private String eventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 15, nullable = false)
    private WebhookStatus status = WebhookStatus.RECEIVED;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;
}
