package com.ladygo.testsupport;

import java.time.Instant;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.config.domain.ConfigEntry;
import com.ladygo.config.domain.ConfigScope;
import com.ladygo.config.repository.ConfigEntryRepository;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.domain.RideStatus;
import org.springframework.beans.factory.annotation.Autowired;

/** Builds already-finished rides directly (no state machine), for history / earnings / rating tests. */
public abstract class RideScenarioSupport extends PersistenceTestSupport {

    @Autowired protected ConfigEntryRepository configEntries;

    /** A COMPLETED ride whose booking awaits payment, with the given fare and completion time. */
    protected Ride completedRide(City city, Customer customer, Driver driver, long fare, Instant completedAt) {
        Booking booking = newBooking(customer, city, BookingStatus.PAYMENT_PENDING, driver.getId());
        booking.setFinalFare(fare);
        bookings.saveAndFlush(booking);
        Ride ride = newRide(booking, driver);
        ride.setStatus(RideStatus.COMPLETED);
        ride.setCompletedAt(completedAt);
        return rides.saveAndFlush(ride);
    }

    protected void setConfig(String key, String value) {
        ConfigEntry entry = configEntries.findByConfigKeyAndScopeAndCityIdIsNull(key, ConfigScope.GLOBAL).orElseThrow();
        entry.setConfigValue(value);
        configEntries.saveAndFlush(entry);
    }
}
