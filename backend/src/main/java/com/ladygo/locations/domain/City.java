package com.ladygo.locations.domain;

import com.ladygo.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Polygon;

@Entity
@Table(name = "cities")
@Getter
@Setter
public class City extends AuditedEntity {

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country", length = 2, nullable = false)
    private String country;

    @Column(name = "timezone", length = 64, nullable = false)
    private String timezone;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "service_area", columnDefinition = "geography(Polygon,4326)")
    private Polygon serviceArea;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
