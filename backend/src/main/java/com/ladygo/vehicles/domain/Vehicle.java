package com.ladygo.vehicles.domain;

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
@Table(name = "vehicles")
@Getter
@Setter
public class Vehicle extends AuditedEntity {

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "registration_number", length = 20, nullable = false)
    private String registrationNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", length = 20, nullable = false)
    private VehicleType vehicleType;

    @Column(name = "make", length = 50)
    private String make;

    @Column(name = "model", length = 50)
    private String model;

    @Column(name = "color", length = 30)
    private String color;

    @Column(name = "capacity", nullable = false)
    private short capacity = (short) 4;

    @Column(name = "manufacture_year")
    private Short manufactureYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", length = 20, nullable = false)
    private VehicleVerificationStatus verificationStatus = VehicleVerificationStatus.PENDING;
}
