package com.ladygo.payments.domain;

import java.util.UUID;
import com.ladygo.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "refunds")
@Getter
@Setter
public class Refund extends AuditedEntity {

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 15, nullable = false)
    private RefundStatus status = RefundStatus.PENDING;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "created_by")
    private UUID createdBy;
}
