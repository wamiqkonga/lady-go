package com.ladygo.payments.domain;

import java.util.UUID;
import com.ladygo.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "payment_methods")
@Getter
@Setter
public class PaymentMethod extends CreatedEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "provider", length = 50, nullable = false)
    private String provider;

    @Column(name = "provider_token", length = 255, nullable = false)
    private String providerToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20, nullable = false)
    private PaymentMethodType type;

    @Column(name = "display_label", length = 50)
    private String displayLabel;

    @Column(name = "is_default", nullable = false)
    private boolean defaultMethod = false;
}
