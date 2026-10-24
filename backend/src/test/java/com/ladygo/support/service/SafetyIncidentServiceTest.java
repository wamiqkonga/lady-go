package com.ladygo.support.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import com.ladygo.rides.domain.Ride;
import com.ladygo.support.domain.SafetyIncident;
import com.ladygo.support.repository.SafetyIncidentRepository;
import com.ladygo.testsupport.PersistenceTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SafetyIncidentServiceTest extends PersistenceTestSupport {

    @Autowired
    private SafetyIncidentService service;
    @Autowired
    private SafetyIncidentRepository incidents;

    private Ride ride(Driver driver) {
        City city = newCity();
        Booking booking = newBooking(newCustomer(), city, BookingStatus.RIDE_STARTED, driver.getId());
        return newRide(booking, driver);
    }

    @Test
    void notesAreLengthLimitedAndALocationlessIncidentIsAllowed() {
        Driver driver = newDriver(newCity());
        Ride ride = ride(driver);

        service.reportCustomerFromDriverEarlyEnd(ride.getId(), driver.getUserId(), "x".repeat(5000), null);

        SafetyIncident incident = incidents.findAll().stream()
                .filter(i -> i.getRideId().equals(ride.getId())).findFirst().orElseThrow();
        assertThat(incident.getNotes()).hasSize(500);
        assertThat(incident.getLocation()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM safety_incident_events WHERE incident_id = ? AND event_type = 'CREATED'",
                Long.class, incident.getId())).isEqualTo(1L);
    }

    @Test
    void aDatabaseProblemIsSwallowedAndRollsBackOnlyTheIncident() {
        Driver driver = newDriver(newCity());

        // Unknown ride id -> FK violation inside the savepoint; must not throw nor poison the caller's transaction.
        service.reportCustomerFromDriverEarlyEnd(UUID.randomUUID(), driver.getUserId(), "x", null);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM safety_incidents WHERE reporter_id = ?",
                Long.class, driver.getUserId())).isZero();
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1); // transaction still usable
    }
}
