package com.ladygo.bookings.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ladygo.bookings.web.dto.BookingRequest;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.locations.domain.City;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.vehicles.domain.VehicleType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * docs/api/booking.md section 7: the placeholder {@link EligibilityPolicy} is wired into booking
 * creation right after the customer is loaded. D5 itself is undecided - this only proves the seam is
 * actually called, using a mocked denial, not any real eligibility rule.
 */
class BookingServiceEligibilityTest extends PersistenceTestSupport {

    @Autowired
    private BookingService bookingService;
    @MockitoBean
    private EligibilityPolicy eligibilityPolicy;

    private BookingRequest bookingRequest() {
        return new BookingRequest(
                34.08, 74.80, "Lal Chowk", 34.13, 74.87, "Hazratbal",
                VehicleType.CAB, PaymentMode.CASH, null, 18500L);
    }

    @Test
    void anIneligibleCustomerIsRejected() {
        newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        when(eligibilityPolicy.checkCustomerEligibility(any())).thenReturn(EligibilityDecision.denied("not eligible"));
        when(eligibilityPolicy.checkCompanions(any())).thenReturn(EligibilityDecision.allowed());

        assertThatThrownBy(() -> bookingService.create(customer.getUserId(), bookingRequest(), UUID.randomUUID().toString()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void ineligibleCompanionsAreRejected() {
        newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        when(eligibilityPolicy.checkCustomerEligibility(any())).thenReturn(EligibilityDecision.allowed());
        when(eligibilityPolicy.checkCompanions(any())).thenReturn(EligibilityDecision.denied("companion not allowed"));

        assertThatThrownBy(() -> bookingService.create(customer.getUserId(), bookingRequest(), UUID.randomUUID().toString()))
                .isInstanceOf(ForbiddenException.class);
    }
}
