package com.ladygo.bookings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ladygo.bookings.web.dto.BookingRequest;
import com.ladygo.bookings.web.dto.FareEstimateRequest;
import com.ladygo.bookings.web.dto.FareEstimateResponse;
import com.ladygo.customers.domain.Customer;
import com.ladygo.locations.domain.City;
import com.ladygo.maps.MapsService;
import com.ladygo.maps.MapsServiceException;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.vehicles.domain.VehicleType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * docs/api/booking.md section 1 vs. section 2 (M3): the estimate endpoint degrades gracefully on a
 * Maps outage, booking creation fails closed. A real {@code MapsServiceException} (not just a mocked
 * return value) proves {@link BookingService} reacts to the exception type, not a sentinel value.
 */
class BookingServiceMapsOutageTest extends PersistenceTestSupport {

    @Autowired
    private BookingService bookingService;
    @MockitoBean
    private MapsService mapsService;

    private City srinagar() {
        return newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
    }

    @Test
    void estimateDegradesToStraightLineDistanceWhenMapsIsDown() {
        City city = srinagar();
        newPricingRule(city, VehicleType.CAB);
        when(mapsService.calculateRoute(any(), any())).thenThrow(new MapsServiceException(new RuntimeException("down")));
        when(mapsService.calculateDistanceMeters(any(), any())).thenReturn(8000.0);

        FareEstimateResponse estimate = bookingService.estimate(
                new FareEstimateRequest(34.08, 74.80, 34.13, 74.87, VehicleType.CAB));

        assertThat(estimate.approximate()).isTrue();
        assertThat(estimate.distanceMeters()).isEqualTo(8000);
    }

    @Test
    void bookingCreationFailsClosedWhenMapsIsDown() {
        City city = srinagar();
        newPricingRule(city, VehicleType.CAB);
        Customer customer = newCustomer();
        when(mapsService.calculateRoute(any(), any())).thenThrow(new MapsServiceException(new RuntimeException("down")));

        BookingRequest request = new BookingRequest(
                34.08, 74.80, "Lal Chowk", 34.13, 74.87, "Hazratbal",
                VehicleType.CAB, PaymentMode.CASH, null, 18500L);

        assertThatThrownBy(() -> bookingService.create(customer.getUserId(), request, UUID.randomUUID().toString()))
                .isInstanceOf(MapsServiceException.class);
    }
}
