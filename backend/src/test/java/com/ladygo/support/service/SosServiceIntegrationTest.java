package com.ladygo.support.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import com.ladygo.support.domain.IncidentSeverity;
import com.ladygo.support.domain.IncidentStatus;
import com.ladygo.support.domain.IncidentType;
import com.ladygo.support.domain.SafetyIncident;
import com.ladygo.support.repository.SafetyIncidentRepository;
import com.ladygo.support.web.dto.SosRequest;
import com.ladygo.support.web.dto.SosResponse;
import com.ladygo.testsupport.PersistenceTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SosServiceIntegrationTest extends PersistenceTestSupport {

    @Autowired
    private SosService service;
    @Autowired
    private SafetyIncidentRepository incidents;

    private static SosRequest at(java.util.UUID bookingId) {
        return new SosRequest(12.97, 77.59, bookingId, "help");
    }

    @Test
    void aRiderSosWithNoBookingCreatesACriticalHeldIncidentAndATimelineEvent() {
        Customer customer = newCustomer();

        SosResponse response = service.raiseForCustomer(customer.getUserId(), at(null));

        SafetyIncident incident = incidents.findById(response.incidentId()).orElseThrow();
        assertThat(incident.getType()).isEqualTo(IncidentType.SOS);
        assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.isLegalHold()).isTrue();
        assertThat(incident.getRideId()).isNull();
        assertThat(incident.getBookingId()).isNull();
        assertThat(incident.getLocation().getY()).isEqualTo(12.97);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM safety_incident_events WHERE incident_id = ? AND event_type = 'CREATED'",
                Long.class, incident.getId())).isEqualTo(1L);
    }

    @Test
    void aRiderSosCanAttachToTheirOwnBookingButNotSomeoneElses() {
        City city = newCity();
        Customer customer = newCustomer();
        Booking own = newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);
        Booking other = newBooking(newCustomer(), city, BookingStatus.SEARCHING_DRIVER, null);

        SosResponse response = service.raiseForCustomer(customer.getUserId(), at(own.getId()));

        assertThat(incidents.findById(response.incidentId()).orElseThrow().getBookingId()).isEqualTo(own.getId());
        assertThatThrownBy(() -> service.raiseForCustomer(customer.getUserId(), at(other.getId())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aDriverSosAttachesToTheRideTheyAreOn() {
        City city = newCity();
        Driver driver = newDriver(city);
        Booking booking = newBooking(newCustomer(), city, BookingStatus.RIDE_STARTED, driver.getId());
        newRide(booking, driver);

        SosResponse response = service.raiseForDriver(driver.getUserId(), at(booking.getId()));

        SafetyIncident incident = incidents.findById(response.incidentId()).orElseThrow();
        assertThat(incident.getBookingId()).isEqualTo(booking.getId());
        assertThat(incident.getRideId()).isNotNull();
    }

    @Test
    void tooManyAlertsInTheWindowAreRateLimited() {
        Customer customer = newCustomer();
        for (int i = 0; i < 3; i++) {
            service.raiseForCustomer(customer.getUserId(), at(null));
        }
        assertThatThrownBy(() -> service.raiseForCustomer(customer.getUserId(), at(null)))
                .isInstanceOf(RateLimitExceededException.class);
    }
}
