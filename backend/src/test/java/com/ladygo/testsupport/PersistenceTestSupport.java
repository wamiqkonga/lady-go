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
import com.ladygo.pricing.domain.PricingRule;
import com.ladygo.pricing.repository.PricingRuleRepository;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.repository.RideRepository;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.repository.UserRepository;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.domain.VehicleType;
import com.ladygo.vehicles.repository.VehicleRepository;
import com.ladygo.verification.domain.SubjectType;
import jakarta.persistence.EntityManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
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
    @Autowired protected PricingRuleRepository pricingRules;
    @Autowired protected VehicleRepository vehicles;

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
        booking.setIdempotencyKey(UUID.randomUUID().toString());
        booking.setStatus(status);
        if (status == BookingStatus.CANCELLED) {
            booking.setCancelledBy(ActorType.CUSTOMER);
        }
        return bookings.saveAndFlush(booking);
    }

    /** A city whose service area is a simple rectangle spanning the given bounding box (lon/lat degrees). */
    protected City newCityWithServiceArea(double lonMin, double latMin, double lonMax, double latMax) {
        City city = newCity();
        Polygon serviceArea = GEOMETRY.createPolygon(new Coordinate[] {
            new Coordinate(lonMin, latMin), new Coordinate(lonMax, latMin),
            new Coordinate(lonMax, latMax), new Coordinate(lonMin, latMax),
            new Coordinate(lonMin, latMin)
        });
        city.setServiceArea(serviceArea);
        return cities.saveAndFlush(city);
    }

    protected PricingRule newPricingRule(City city, VehicleType vehicleType) {
        PricingRule rule = new PricingRule();
        rule.setCityId(city.getId());
        rule.setVehicleType(vehicleType);
        rule.setCurrency("INR");
        rule.setBaseFare(5000);
        rule.setPerKm(1200);
        rule.setPerMinute(200);
        rule.setMinimumFare(6000);
        rule.setCancellationFee(5000);
        rule.setServiceFeePct(BigDecimal.valueOf(5));
        rule.setEffectiveFrom(Instant.now().minusSeconds(3600));
        return pricingRules.saveAndFlush(rule);
    }

    protected Vehicle newVehicle(Driver driver) {
        Vehicle vehicle = new Vehicle();
        vehicle.setDriverId(driver.getId());
        vehicle.setRegistrationNumber("JK01AB" + SEQUENCE.incrementAndGet());
        vehicle.setVehicleType(VehicleType.CAB);
        vehicle.setMake("Maruti");
        vehicle.setModel("Dzire");
        vehicle.setColor("White");
        return vehicles.saveAndFlush(vehicle);
    }

    protected Ride newRide(Booking booking, Driver driver) {
        Ride ride = new Ride();
        ride.setBookingId(booking.getId());
        ride.setDriverId(driver.getId());
        ride.setCustomerId(booking.getCustomerId());
        return rides.saveAndFlush(ride);
    }

    /** Accepts the current self-declaration for a subject - docs/api/driver-onboarding.md section 2. */
    protected void acceptCurrentDeclaration(SubjectType subjectType, UUID subjectId) {
        jdbc.update("INSERT INTO self_declarations (subject_type, subject_id, declaration_version) "
                        + "VALUES (?, ?, (SELECT version FROM declaration_versions ORDER BY published_at DESC LIMIT 1))",
                subjectType.name(), subjectId);
    }

    /** A real, tiny, decodable JPEG - {@link com.ladygo.storage.UploadValidator} now actually decodes
     *  uploads (adversarial review finding, CRITICAL: a declared Content-Type was previously trusted
     *  with no check the bytes were a real image), so placeholder byte arrays no longer pass. */
    protected static byte[] realJpegBytes() {
        try {
            BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A fresh, unused ride selfie for a customer - docs/api/driver-onboarding.md section 4. */
    protected UUID newRideSelfie(UUID customerId) {
        return jdbc.queryForObject(
                "INSERT INTO ride_selfies (customer_id, selfie_storage_key) VALUES (?, ?) RETURNING id",
                UUID.class, customerId, "test-selfie-key-" + SEQUENCE.incrementAndGet());
    }
}
