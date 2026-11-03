package com.ladygo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.common.exception.ErrorCode;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.locations.domain.City;
import com.ladygo.notifications.email.LoggingEmailSender;
import com.ladygo.testsupport.IntegrationTest;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.domain.VehicleVerificationStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** Only fully approved drivers get tokens from the driver app; everyone else gets a reason the app can show. */
@IntegrationTest
@Transactional
@TestPropertySource(properties = "ladygo.otp.channel=email")
class DriverAppLoginIntegrationTest extends PersistenceTestSupport {

    @Autowired
    private OtpLoginService otpLoginService;

    private record Setup(User user, Driver driver, String email) {
    }

    private Setup driver(DriverVerificationStatus status, VehicleVerificationStatus vehicleStatus) {
        City city = newCity();
        Driver driver = newDriver(city);
        driver.setVerificationStatus(status);
        drivers.saveAndFlush(driver);
        User user = users.findById(driver.getUserId()).orElseThrow();
        String email = "driver-" + UUID.randomUUID() + "@example.test";
        user.setEmail(email);
        users.saveAndFlush(user);
        if (vehicleStatus != null) {
            Vehicle vehicle = newVehicle(driver);
            vehicle.setVerificationStatus(vehicleStatus);
            vehicles.saveAndFlush(vehicle);
        }
        return new Setup(user, driver, email);
    }

    private String codeFor(Setup setup) {
        otpLoginService.requestOtp(setup.user().getPhone(), com.ladygo.auth.domain.OtpPurpose.LOGIN,
                UserRole.DRIVER, "1.2.3.4", null, setup.email());
        return LoggingEmailSender.lastCodeFor(setup.email());
    }

    private void assertBlocked(Setup setup, ErrorCode expected) {
        String code = codeFor(setup);
        assertThatThrownBy(() -> otpLoginService.verifyDriverAppOtp(setup.user().getPhone(), code, "d"))
                .isInstanceOfSatisfying(DriverLoginBlockedException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    @Test
    void anApprovedDriverWithAnApprovedVehicleIsLoggedIn() {
        Setup setup = driver(DriverVerificationStatus.APPROVED, VehicleVerificationStatus.APPROVED);
        String code = codeFor(setup);

        TokenPair tokens = otpLoginService.verifyDriverAppOtp(setup.user().getPhone(), code, "d");

        assertThat(tokens.accessToken()).isNotBlank();
    }

    @Test
    void aDriverWhoseDocumentsAreUnderReviewIsToldSo() {
        assertBlocked(driver(DriverVerificationStatus.PENDING_REVIEW, VehicleVerificationStatus.PENDING),
                ErrorCode.DRIVER_UNDER_REVIEW);
    }

    @Test
    void anApprovedDriverWhoseVehicleIsStillUnderReviewIsAlsoToldSo() {
        assertBlocked(driver(DriverVerificationStatus.APPROVED, VehicleVerificationStatus.PENDING),
                ErrorCode.DRIVER_UNDER_REVIEW);
        assertBlocked(driver(DriverVerificationStatus.APPROVED, null), ErrorCode.DRIVER_UNDER_REVIEW);
    }

    @Test
    void anUnfinishedRegistrationIsSentBackToTheWebsite() {
        assertBlocked(driver(DriverVerificationStatus.NOT_SUBMITTED, null), ErrorCode.DRIVER_NOT_REGISTERED);
    }

    @Test
    void aRejectedAndASuspendedDriverAreToldToContactSupport() {
        assertBlocked(driver(DriverVerificationStatus.REJECTED, VehicleVerificationStatus.APPROVED),
                ErrorCode.DRIVER_REJECTED);
        assertBlocked(driver(DriverVerificationStatus.SUSPENDED, VehicleVerificationStatus.APPROVED),
                ErrorCode.DRIVER_SUSPENDED);
    }

    @Test
    void theDriverAppNeverCreatesAnAccountForAnUnknownNumber() {
        String phone = "+919477700001";
        String email = "ghost-" + UUID.randomUUID() + "@example.test";
        otpLoginService.requestOtp(phone, com.ladygo.auth.domain.OtpPurpose.LOGIN, UserRole.DRIVER,
                "1.2.3.4", null, email);
        String code = LoggingEmailSender.lastCodeFor(email);

        assertThatThrownBy(() -> otpLoginService.verifyDriverAppOtp(phone, code, "d"))
                .isInstanceOfSatisfying(DriverLoginBlockedException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DRIVER_NOT_REGISTERED));
        assertThat(users.findByPhone(phone)).isEmpty();
    }

    @Test
    void aWrongCodeStillRevealsNothingAboutTheDriversStatus() {
        Setup setup = driver(DriverVerificationStatus.PENDING_REVIEW, VehicleVerificationStatus.PENDING);
        String code = codeFor(setup);
        String wrong = code.equals("000000") ? "111111" : "000000";

        assertThatThrownBy(() -> otpLoginService.verifyDriverAppOtp(setup.user().getPhone(), wrong, "d"))
                .isInstanceOf(OtpVerificationException.class);
    }
}
