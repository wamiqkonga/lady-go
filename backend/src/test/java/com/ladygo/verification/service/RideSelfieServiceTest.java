package com.ladygo.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.locations.domain.City;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.verification.web.dto.RideSelfieResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

class RideSelfieServiceTest extends PersistenceTestSupport {

    @Autowired
    private RideSelfieService service;

    private MockMultipartFile jpegFile() {
        return new MockMultipartFile("file", "selfie.jpg", "image/jpeg", "fake-jpeg-bytes".getBytes());
    }

    private Booking newBookingFor(Customer customer) {
        City city = newCity();
        return newBooking(customer, city, BookingStatus.REQUESTED, null);
    }

    @Test
    void captureStoresAnUnusedFreshSelfie() {
        Customer customer = newCustomer();

        RideSelfieResponse response = service.capture(customer.getId(), jpegFile());

        assertThat(response.rideSelfieId()).isNotNull();
        assertThat(response.capturedAt()).isNotNull();
    }

    @Test
    void captureRejectsANonImageContentType() {
        Customer customer = newCustomer();
        MockMultipartFile pdf = new MockMultipartFile("file", "doc.pdf", "application/pdf", "x".getBytes());

        assertThatThrownBy(() -> service.capture(customer.getId(), pdf)).isInstanceOf(ValidationException.class);
    }

    @Test
    void captureRejectsAnOversizedFile() {
        Customer customer = newCustomer();
        jdbc.update("UPDATE configuration SET value = '10' WHERE key = 'verification.upload.max_size.bytes'");
        MockMultipartFile tooBig = new MockMultipartFile(
                "file", "selfie.jpg", "image/jpeg", "this is way more than ten bytes".getBytes());

        assertThatThrownBy(() -> service.capture(customer.getId(), tooBig)).isInstanceOf(ValidationException.class);
    }

    @Test
    void claimForBookingSucceedsOnceThenFailsOnReuse() {
        Customer customer = newCustomer();
        Booking first = newBookingFor(customer);
        Booking second = newBookingFor(customer);
        RideSelfieResponse captured = service.capture(customer.getId(), jpegFile());

        service.claimForBooking(captured.rideSelfieId(), customer.getId(), first.getId());

        assertThatThrownBy(() -> service.claimForBooking(captured.rideSelfieId(), customer.getId(), second.getId()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void claimForBookingFailsForTheWrongCustomer() {
        Customer customer = newCustomer();
        Customer stranger = newCustomer();
        Booking booking = newBookingFor(stranger);
        RideSelfieResponse captured = service.capture(customer.getId(), jpegFile());

        assertThatThrownBy(() -> service.claimForBooking(captured.rideSelfieId(), stranger.getId(), booking.getId()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void claimForBookingFailsForAStaleSelfie() {
        Customer customer = newCustomer();
        Booking booking = newBookingFor(customer);
        RideSelfieResponse captured = service.capture(customer.getId(), jpegFile());
        jdbc.update("UPDATE ride_selfies SET captured_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), captured.rideSelfieId());

        assertThatThrownBy(() -> service.claimForBooking(captured.rideSelfieId(), customer.getId(), booking.getId()))
                .isInstanceOf(ValidationException.class);
    }
}
