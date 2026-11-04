package com.ladygo.auth.service;

import com.ladygo.common.exception.ErrorCode;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserStatus;
import com.ladygo.vehicles.domain.VehicleVerificationStatus;
import com.ladygo.vehicles.repository.VehicleRepository;
import org.springframework.stereotype.Component;

/**
 * Who may use the driver app: an active user whose driver record is APPROVED and who has an approved vehicle. Applied to
 * every driver-app sign-in (code and password) after the person has proved who she is, never before, so it cannot be used
 * to find out which phone numbers are drivers. The website's own sign-in is not gated: drivers must be able to log in
 * there to register and upload documents.
 */
@Component
public class DriverApprovalGate {

    private final DriverRepository drivers;
    private final VehicleRepository vehicles;

    public DriverApprovalGate(DriverRepository drivers, VehicleRepository vehicles) {
        this.drivers = drivers;
        this.vehicles = vehicles;
    }

    public void requireApproved(User user) {
        if (user == null) {
            throw new DriverLoginBlockedException(ErrorCode.DRIVER_NOT_REGISTERED);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("This account is not active.");
        }
        Driver driver = drivers.findByUserId(user.getId()).orElse(null);
        if (driver == null) {
            throw new DriverLoginBlockedException(ErrorCode.DRIVER_NOT_REGISTERED);
        }
        DriverVerificationStatus status = driver.getVerificationStatus();
        if (status == DriverVerificationStatus.APPROVED) {
            boolean hasApprovedVehicle = vehicles.findByDriverId(driver.getId()).stream()
                    .anyMatch(v -> v.getVerificationStatus() == VehicleVerificationStatus.APPROVED);
            if (!hasApprovedVehicle) {
                throw new DriverLoginBlockedException(ErrorCode.DRIVER_UNDER_REVIEW);
            }
            return;
        }
        if (status == DriverVerificationStatus.PENDING_REVIEW) {
            throw new DriverLoginBlockedException(ErrorCode.DRIVER_UNDER_REVIEW);
        }
        if (status == DriverVerificationStatus.REJECTED) {
            throw new DriverLoginBlockedException(ErrorCode.DRIVER_REJECTED);
        }
        if (status == DriverVerificationStatus.SUSPENDED) {
            throw new DriverLoginBlockedException(ErrorCode.DRIVER_SUSPENDED);
        }
        throw new DriverLoginBlockedException(ErrorCode.DRIVER_NOT_REGISTERED); // NOT_SUBMITTED: registration unfinished
    }
}
