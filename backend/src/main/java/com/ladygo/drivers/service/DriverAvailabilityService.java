package com.ladygo.drivers.service;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.drivers.web.dto.DriverAvailabilityResponse;
import com.ladygo.vehicles.domain.VehicleVerificationStatus;
import com.ladygo.vehicles.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * docs/api/matching.md section 2 - going online requires an approved driver AND an approved vehicle,
 * and is blocked while a re-verification is pending (the Phase 5 field this phase finally consults).
 * {@code BUSY}/{@code ON_RIDE}/{@code SUSPENDED} are never driver-settable here - they're
 * system/admin-controlled states.
 */
@Service
public class DriverAvailabilityService {

    private final DriverRepository drivers;
    private final VehicleRepository vehicles;
    private final DriverReverificationService reverificationService;

    public DriverAvailabilityService(
            DriverRepository drivers, VehicleRepository vehicles, DriverReverificationService reverificationService) {
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.reverificationService = reverificationService;
    }

    @Transactional
    public DriverAvailabilityResponse setAvailability(UUID userId, DriverAvailability requested) {
        if (requested != DriverAvailability.ONLINE && requested != DriverAvailability.OFFLINE) {
            throw new ValidationException("status must be ONLINE or OFFLINE.");
        }
        Driver driver = drivers.findByUserId(userId).orElseThrow(ResourceNotFoundException::new);
        DriverAvailability current = driver.getAvailabilityStatus();

        if (current != DriverAvailability.ONLINE && current != DriverAvailability.OFFLINE) {
            throw new ForbiddenException("Availability cannot be changed directly while " + current + ".");
        }
        if (requested == DriverAvailability.OFFLINE) {
            driver.setAvailabilityStatus(DriverAvailability.OFFLINE);
            return toResponse(drivers.save(driver));
        }

        if (driver.getVerificationStatus() != DriverVerificationStatus.APPROVED) {
            throw new ForbiddenException("Driver is not approved.");
        }
        boolean hasApprovedVehicle = vehicles.findByDriverId(driver.getId()).stream()
                .anyMatch(v -> v.getVerificationStatus() == VehicleVerificationStatus.APPROVED);
        if (!hasApprovedVehicle) {
            throw new ForbiddenException("No approved vehicle on file.");
        }
        if (reverificationService.isBlockedFromGoingOnline(driver.getId(), Instant.now())) {
            throw new ForbiddenException("Re-verification required before going online.");
        }

        driver.setAvailabilityStatus(DriverAvailability.ONLINE);
        return toResponse(drivers.save(driver));
    }

    private static DriverAvailabilityResponse toResponse(Driver driver) {
        return new DriverAvailabilityResponse(driver.getAvailabilityStatus());
    }
}
