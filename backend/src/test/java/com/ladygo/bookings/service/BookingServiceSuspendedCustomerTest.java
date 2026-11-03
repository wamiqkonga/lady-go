package com.ladygo.bookings.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.bookings.web.dto.BookingRequest;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserStatus;
import com.ladygo.vehicles.domain.VehicleType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A suspended customer's access token stays valid until it expires, so the booking path itself must
 * check the account status (refresh and OTP sign-in already do).
 */
class BookingServiceSuspendedCustomerTest extends PersistenceTestSupport {

    @Autowired
    private BookingService bookingService;

    private BookingRequest bookingRequest() {
        return new BookingRequest(
                34.08, 74.80, "Lal Chowk", 34.13, 74.87, "Hazratbal",
                VehicleType.CAB, PaymentMode.CASH, null, 18500L, UUID.randomUUID());
    }

    @Test
    void aSuspendedCustomerCannotCreateABooking() {
        Customer customer = newCustomer();
        User user = users.findById(customer.getUserId()).orElseThrow();
        user.setStatus(UserStatus.SUSPENDED);
        users.saveAndFlush(user);

        assertThatThrownBy(() -> bookingService.create(customer.getUserId(), bookingRequest(), UUID.randomUUID().toString()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not active");
    }
}
