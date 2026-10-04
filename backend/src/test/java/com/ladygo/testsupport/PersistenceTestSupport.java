package com.ladygo.testsupport;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.common.domain.ActorType;
import com.ladygo.customers.domain.Customer;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.locations.domain.City;
import com.ladygo.locations.repository.CityRepository;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.repository.RideRepository;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.repository.UserRepository;
import com.ladygo.vehicles.domain.VehicleType;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Base for database tests. Every test runs in a transaction that is rolled back, so tests never see each
 * other's data. Provides small factories for the rows most tests need.
 */
@IntegrationTest
@Transactional
public abstract class PersistenceTestSupport {

    private static final GeometryFactory GEOMETRY = new GeometryFactory(new PrecisionModel(), 4326);
    private static final AtomicInteger SEQUENCE = new AtomicInteger(1000);

    @Autowired protected EntityManager em;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserRepository users;
    @Autowired protected CityRepository cities;
    @Autowired protected CustomerRepository customers;
    @Autowired protected DriverRepository drivers;
    @Autowired protected BookingRepository bookings;
    @Autowired protected RideRepository rides;

    /** WGS84 point; note the argument order is longitude first, then latitude. */
    protected static Point point(double lon, double lat) {
        return GEOMETRY.createPoint(new Coordinate(lon, lat));
    }

    protected static String nextPhone() {
        return "+9198" + String.format("%08d", SEQUENCE.incrementAndGet());
    }

    protected void flushAndClear() {
        em.flush();
        em.clear();
    }

    protected User newUser(UserRole role) {
        User user = new User();
        user.setPhone(nextPhone());
        user.setRole(role);
        user.setName("Test " + role);
        return users.saveAndFlush(user);
    }

    protected City newCity() {
        City city = new City();
        city.setName("City-" + SEQUENCE.incrementAndGet());
        city.setCountry("IN");
        city.setTimezone("Asia/Kolkata");
        city.setCurrency("INR");
        return cities.saveAndFlush(city);
    }

    protected Customer newCustomer() {
        Customer customer = new Customer();
        customer.setUserId(newUser(UserRole.CUSTOMER).getId());
        return customers.saveAndFlush(customer);
    }

    protected Driver newDriver(City city) {
        Driver driver = new Driver();
        driver.setUserId(newUser(UserRole.DRIVER).getId());
        driver.setCityId(city.getId());
        return drivers.saveAndFlush(driver);
    }

    /** An approved driver, online at the given position. */
    protected Driver newOnlineDriver(City city, double lon, double lat) {
        Driver driver = new Driver();
        driver.setUserId(newUser(UserRole.DRIVER).getId());
        driver.setCityId(city.getId());
        driver.setVerificationStatus(DriverVerificationStatus.APPROVED);
        driver.setAvailabilityStatus(DriverAvailability.ONLINE);
        driver.setCurrentLocation(point(lon, lat));
        return drivers.saveAndFlush(driver);
    }

    protected Booking newBooking(Customer customer, City city, BookingStatus status, UUID driverId) {
        Booking booking = new Booking();
        booking.setCustomerId(customer.getId());
        booking.setCityId(city.getId());
        booking.setDriverId(driverId);
        booking.setVehicleType(VehicleType.CAB);
        booking.setPickupLocation(point(74.80, 34.08));
        booking.setPickupAddress("Lal Chowk");
        booking.setDestinationLocation(point(74.87, 34.13));
        booking.setDestinationAddress("Hazratbal");
        booking.setCurrency("INR");
        booking.setPaymentMethod(PaymentMode.CASH);
        booking.setStatus(status);
        if (status == BookingStatus.CANCELLED) {
            booking.setCancelledBy(ActorType.CUSTOMER);
        }
        return bookings.saveAndFlush(booking);
    }

    protected Ride newRide(Booking booking, Driver driver) {
        Ride ride = new Ride();
        ride.setBookingId(booking.getId());
        ride.setDriverId(driver.getId());
        ride.setCustomerId(booking.getCustomerId());
        return rides.saveAndFlush(ride);
    }
}
