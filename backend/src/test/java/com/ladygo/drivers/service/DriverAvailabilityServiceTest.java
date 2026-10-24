package com.ladygo.drivers.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.locations.domain.City;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.domain.VehicleVerificationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** docs/api/matching.md section 2. */
class DriverAvailabilityServiceTest extends PersistenceTestSupport {

    @Autowired
    private DriverAvailabilityService service;
    @Autowired
    private DriverReverificationService reverificationService;

    private Driver approvedDriverWithApprovedVehicle(City city) {
        Driver driver = newDriver(city);
        driver.setVerificationStatus(DriverVerificationStatus.APPROVED);
        drivers.saveAndFlush(driver);
        Vehicle vehicle = newVehicle(driver);
        vehicle.setVerificationStatus(VehicleVerificationStatus.APPROVED);
        vehicles.saveAndFlush(vehicle);
        return driver;
    }

    @Test
    void anApprovedDriverWithAnApprovedVehicleCanGoOnline() {
        Driver driver = approvedDriverWithApprovedVehicle(newCity());

        var response = service.setAvailability(driver.getUserId(), DriverAvailability.ONLINE);

        assertThat(response.status()).isEqualTo(DriverAvailability.ONLINE);
        assertThat(drivers.findById(driver.getId()).orElseThrow().getAvailabilityStatus())
                .isEqualTo(DriverAvailability.ONLINE);
    }

    @Test
    void anUnapprovedDriverCannotGoOnline() {
        Driver driver = newDriver(newCity()); // NOT_SUBMITTED by default
        newVehicle(driver); // PENDING by default

        assertThatThrownBy(() -> service.setAvailability(driver.getUserId(), DriverAvailability.ONLINE))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void anApprovedDriverWithNoApprovedVehicleCannotGoOnline() {
        Driver driver = newDriver(newCity());
        driver.setVerificationStatus(DriverVerificationStatus.APPROVED);
        drivers.saveAndFlush(driver);
        // no vehicle at all

        assertThatThrownBy(() -> service.setAvailability(driver.getUserId(), DriverAvailability.ONLINE))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aDriverBlockedByAnImmediateReportCannotGoOnline() {
        Driver driver = approvedDriverWithApprovedVehicle(newCity());
        reverificationService.blockImmediatelyForReport(driver.getId()); // report-triggered: no grace period

        assertThatThrownBy(() -> service.setAvailability(driver.getUserId(), DriverAvailability.ONLINE))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aDriverWithinTheRandomReverificationGracePeriodCanStillGoOnline() {
        Driver driver = approvedDriverWithApprovedVehicle(newCity());
        reverificationService.triggerRandomReverification(driver.getId()); // 24h grace - not blocked yet

        var response = service.setAvailability(driver.getUserId(), DriverAvailability.ONLINE);

        assertThat(response.status()).isEqualTo(DriverAvailability.ONLINE);
    }

    @Test
    void goingOfflineIsAlwaysAllowedFromOnline() {
        Driver driver = approvedDriverWithApprovedVehicle(newCity());
        service.setAvailability(driver.getUserId(), DriverAvailability.ONLINE);

        service.setAvailability(driver.getUserId(), DriverAvailability.OFFLINE);

        assertThat(drivers.findById(driver.getId()).orElseThrow().getAvailabilityStatus())
                .isEqualTo(DriverAvailability.OFFLINE);
    }

    @Test
    void aDriverOnAnActiveRideCannotGoOffline() {
        Driver driver = approvedDriverWithApprovedVehicle(newCity());
        driver.setAvailabilityStatus(DriverAvailability.ON_RIDE);
        drivers.saveAndFlush(driver);

        assertThatThrownBy(() -> service.setAvailability(driver.getUserId(), DriverAvailability.OFFLINE))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aBusyDriverCannotChangeAvailabilityDirectly() {
        Driver driver = approvedDriverWithApprovedVehicle(newCity());
        driver.setAvailabilityStatus(DriverAvailability.BUSY);
        drivers.saveAndFlush(driver);

        assertThatThrownBy(() -> service.setAvailability(driver.getUserId(), DriverAvailability.ONLINE))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getAvailabilityReturnsTheStoredStatus() {
        Driver driver = approvedDriverWithApprovedVehicle(newCity());
        assertThat(service.getAvailability(driver.getUserId()).status()).isEqualTo(DriverAvailability.OFFLINE);

        driver.setAvailabilityStatus(DriverAvailability.ON_RIDE);
        drivers.saveAndFlush(driver);
        assertThat(service.getAvailability(driver.getUserId()).status()).isEqualTo(DriverAvailability.ON_RIDE);
    }

    @Test
    void getAvailabilityForAnUnknownDriverIsNotFound() {
        assertThatThrownBy(() -> service.getAvailability(java.util.UUID.randomUUID()))
                .isInstanceOf(com.ladygo.common.exception.ResourceNotFoundException.class);
    }
}
