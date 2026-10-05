package com.ladygo.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.service.DriverReverificationService;
import com.ladygo.locations.domain.City;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.verification.domain.EligibilityReport;
import com.ladygo.verification.repository.EligibilityReportRepository;
import com.ladygo.verification.web.dto.EligibilityReportResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * docs/api/driver-onboarding.md section 5: always scoped to a shared booking, identified to admins,
 * anonymous to the reported person, rate-limited.
 */
class EligibilityReportServiceTest extends PersistenceTestSupport {

    @Autowired
    private EligibilityReportService service;
    @Autowired
    private EligibilityReportRepository reports;
    @Autowired
    private DriverReverificationService reverificationService;

    @Test
    void theCustomerOnABookingCanReportItsDriver() {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        EligibilityReportResponse response = service.report(customer.getUserId(), booking.getId(), "concern");

        EligibilityReport saved = reports.findById(response.id()).orElseThrow();
        assertThat(saved.getReporterId()).isEqualTo(customer.getUserId());
        assertThat(saved.getSubjectId()).isEqualTo(driver.getId());
        assertThat(saved.isAutoCreated()).isFalse();
    }

    @Test
    void theDriverOnABookingCanReportItsCustomer() {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        EligibilityReportResponse response = service.report(driver.getUserId(), booking.getId(), "concern");

        EligibilityReport saved = reports.findById(response.id()).orElseThrow();
        assertThat(saved.getSubjectId()).isEqualTo(customer.getId());
    }

    @Test
    void reportingADriverImmediatelyBlocksThemFromGoingOnline() {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        service.report(customer.getUserId(), booking.getId(), "concern");

        assertThat(reverificationService.isBlockedFromGoingOnline(driver.getId(), Instant.now())).isTrue();
    }

    @Test
    void someoneNotPartyToTheBookingCannotReportIt() {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());
        Customer stranger = newCustomer();

        assertThatThrownBy(() -> service.report(stranger.getUserId(), booking.getId(), "concern"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reportingABookingWithNoAssignedDriverIsRejected() {
        City city = newCity();
        Customer customer = newCustomer();
        Booking booking = newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);

        assertThatThrownBy(() -> service.report(customer.getUserId(), booking.getId(), "concern"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void reportsBeyondTheConfiguredRateLimitAreRejected() {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        jdbc.update("UPDATE configuration SET value = '1' WHERE key = 'eligibility_report.rate.max.attempts'");
        Booking booking1 = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());
        Booking booking2 = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        service.report(customer.getUserId(), booking1.getId(), "first");

        assertThatThrownBy(() -> service.report(customer.getUserId(), booking2.getId(), "second"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void autoCreatedReportsAreNotRateLimited() {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        jdbc.update("UPDATE configuration SET value = '1' WHERE key = 'eligibility_report.rate.max.attempts'");
        Booking booking1 = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());
        Booking booking2 = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        service.autoReportFromDriverCancellation(booking1.getId(), driver.getUserId(), customer.getId(), "first");
        service.autoReportFromDriverCancellation(booking2.getId(), driver.getUserId(), customer.getId(), "second");

        assertThat(reports.findAll()).hasSizeGreaterThanOrEqualTo(2);
    }
}
