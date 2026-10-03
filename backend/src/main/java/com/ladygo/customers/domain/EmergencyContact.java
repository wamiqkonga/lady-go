package com.ladygo.customers.domain;

import java.util.UUID;
import com.ladygo.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "emergency_contacts")
@Getter
@Setter
public class EmergencyContact extends CreatedEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "phone", length = 16, nullable = false)
    private String phone;

    @Column(name = "relationship", length = 50)
    private String relationship;
}
