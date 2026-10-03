package com.ladygo.vehicles.repository;

import java.util.List;
import java.util.UUID;
import com.ladygo.vehicles.domain.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    List<Vehicle> findByDriverId(UUID driverId);

    boolean existsByRegistrationNumberIgnoreCase(String registrationNumber);
}
