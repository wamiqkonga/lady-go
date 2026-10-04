package com.ladygo.customers.domain;

import java.util.UUID;
import com.ladygo.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "saved_locations")
@Getter
@Setter
public class SavedLocation extends CreatedEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "label", length = 50, nullable = false)
    private String label;

    @Column(name = "address", columnDefinition = "text", nullable = false)
    private String address;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "location", columnDefinition = "geography(Point,4326)", nullable = false)
    private Point location;
}
