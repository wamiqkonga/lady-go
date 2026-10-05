package com.ladygo.bookings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import com.ladygo.storage.FileStorage;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.verification.domain.RideSelfie;
import com.ladygo.verification.repository.RideSelfieRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * docs/api/driver-onboarding.md section 4 - visible only to the accepting driver, only between
 * acceptance and ride start.
 */
class RiderSelfieDeliveryServiceTest extends PersistenceTestSupport {

    @Autowired
    private RiderSelfieDeliveryService service;
    @Autowired
    private RideSelfieRepository rideSelfies;
    @Autowired
    private FileStorage fileStorage;

    private byte[] realPngBytes() throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private RideSelfie attachSelfie(Booking booking, Customer customer) throws Exception {
        String key = "ride-selfies/" + java.util.UUID.randomUUID();
        fileStorage.upload(key, new java.io.ByteArrayInputStream(realPngBytes()), realPngBytes().length, "image/png");
        RideSelfie selfie = new RideSelfie();
        selfie.setCustomerId(customer.getId());
        selfie.setBookingId(booking.getId());
        selfie.setSelfieStorageKey(key);
        return rideSelfies.saveAndFlush(selfie);
    }

    @Test
    void deliversASignedUrlWhenAcceptedAndBeforeRideStart() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        attachSelfie(booking, customer);

        var url = service.deliverFor(driver.getUserId(), booking.getId());

        assertThat(url).isNotNull();
    }

    @Test
    void notVisibleBeforeAcceptance() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());
        attachSelfie(booking, customer);

        assertThatThrownBy(() -> service.deliverFor(driver.getUserId(), booking.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void notVisibleOnceRideHasStarted() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId());
        attachSelfie(booking, customer);

        assertThatThrownBy(() -> service.deliverFor(driver.getUserId(), booking.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void notVisibleToADifferentDriver() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Driver otherDriver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        attachSelfie(booking, customer);

        assertThatThrownBy(() -> service.deliverFor(otherDriver.getUserId(), booking.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void failsCleanlyWhenNoSelfieWasAttached() {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());

        assertThatThrownBy(() -> service.deliverFor(driver.getUserId(), booking.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
