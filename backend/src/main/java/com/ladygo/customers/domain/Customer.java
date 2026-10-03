package com.ladygo.customers.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.ladygo.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "customers")
@Getter
@Setter
public class Customer extends AuditedEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "default_city_id")
    private UUID defaultCityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preferences", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> preferences = new HashMap<>();
}
