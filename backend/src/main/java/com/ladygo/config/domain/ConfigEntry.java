package com.ladygo.config.domain;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "configuration")
@Getter
@Setter
public class ConfigEntry extends BaseEntity {

    @Column(name = "key", length = 150, nullable = false)
    private String configKey;

    @Column(name = "value", columnDefinition = "text", nullable = false)
    private String configValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", length = 10, nullable = false)
    private ConfigValueType valueType = ConfigValueType.STRING;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", length = 10, nullable = false)
    private ConfigScope scope;

    @Column(name = "city_id")
    private UUID cityId;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
