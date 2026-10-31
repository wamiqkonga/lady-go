package com.ladygo.bookings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.bookings.web.dto.DriverLocationForRiderResponse;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.geo.GeoPoints;
import com.ladygo.config.ConfigurationService;
import com.ladygo.customers.domain.Customer;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Who may see the driver's position, when, and how fresh it must be - the privacy rules of the live map. */
class RiderDriverLocationServiceTest {

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final DriverRepository drivers = mock(DriverRepository.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final ConfigurationService config = mock(ConfigurationService.class);
    private final RiderDriverLocationService service =
            new RiderDriverLocationService(customers, bookings, drivers, rateLimiter, config);

    private final UUID userId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final Booking booking = mock(Booking.class);
    private final Driver driver = mock(Driver.class);

    RiderDriverLocationServiceTest() {
        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn(customerId);
        when(customers.findByUserId(userId)).thenReturn(Optional.of(customer));
        when(bookings.findById(bookingId)).thenReturn(Optional.of(booking));
        when(booking.getCustomerId()).thenReturn(customerId);
        when(booking.getStatus()).thenReturn(BookingStatus.DRIVER_ARRIVING);
        when(booking.getDriverId()).thenReturn(driverId);
        when(drivers.findById(driverId)).thenReturn(Optional.of(driver));
        when(driver.getCurrentLocation()).thenReturn(GeoPoints.of(34.08, 74.80));
        when(driver.getCurrentLocationAt()).thenReturn(Instant.now().minusSeconds(5));
        when(config.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(config.getMinutes(anyString(), any(Duration.class))).thenAnswer(inv -> inv.getArgument(1));
        when(config.getSeconds(anyString(), any(Duration.class))).thenAnswer(inv -> inv.getArgument(1));
        when(rateLimiter.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    }

    @Test
    void aFreshPositionDuringALiveRideIsReturned() {
        DriverLocationForRiderResponse response = service.get(userId, bookingId);

        assertThat(response.live()).isTrue();
        assertThat(response.latitude()).isEqualTo(34.08);
        assertThat(response.longitude()).isEqualTo(74.80);
        assertThat(response.updatedAt()).isNotNull();
    }

    @Test
    void anotherRidersBookingLooksLikeItDoesNotExist() {
        when(booking.getCustomerId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.get(userId, bookingId)).isInstanceOf(ResourceNotFoundException.class);
        verify(drivers, never()).findById(any());
    }

    @Test
    void anUnknownBookingIsNotFound() {
        when(bookings.findById(bookingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(userId, bookingId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void beforeTheDriverAcceptsOrAfterTheRideEndsNothingIsShownAndTheDriverRowIsNeverRead() {
        for (BookingStatus status : new BookingStatus[] {
            BookingStatus.SEARCHING_DRIVER, BookingStatus.DRIVER_ASSIGNED, BookingStatus.RIDE_COMPLETED,
            BookingStatus.COMPLETED, BookingStatus.CANCELLED}) {
            when(booking.getStatus()).thenReturn(status);

            assertThat(service.get(userId, bookingId).live()).as(status.name()).isFalse();
        }
        verify(drivers, never()).findById(any());
    }

    @Test
    void aStalePositionIsNeverDrawnAsCurrent() {
        when(driver.getCurrentLocationAt()).thenReturn(Instant.now().minusSeconds(600));

        DriverLocationForRiderResponse response = service.get(userId, bookingId);

        assertThat(response.live()).isFalse();
        assertThat(response.latitude()).isNull();
        assertThat(response.longitude()).isNull();
    }

    @Test
    void aDriverWhoHasNeverReportedHasNothingToShow() {
        when(driver.getCurrentLocation()).thenReturn(null);

        assertThat(service.get(userId, bookingId).live()).isFalse();
    }

    @Test
    void aRiderPollingTooOftenIsRateLimited() {
        when(rateLimiter.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> service.get(userId, bookingId)).isInstanceOf(RateLimitExceededException.class);
    }
}
