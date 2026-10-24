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
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class RiderSelfieDeliveryServiceTest extends PersistenceTestSupport {

    @Autowired
    private RiderSelfieDeliveryService service;
    @Autowired
    private RideSelfieRepository rideSelfies;
    @Autowired
    private FileStorage fileStorage;
    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

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

    /** A photo-like 2000x1500 image: smooth gradients plus mild noise (so it does not compress to nothing). */
    private byte[] largePhotoLikePng() throws Exception {
        BufferedImage image = new BufferedImage(2000, 1500, BufferedImage.TYPE_INT_RGB);
        java.util.Random rnd = new java.util.Random(42);
        for (int y = 0; y < 1500; y++) {
            for (int x = 0; x < 2000; x++) {
                int r = Math.min(255, (x * 255 / 2000) + rnd.nextInt(12));
                int g = Math.min(255, (y * 255 / 1500) + rnd.nextInt(12));
                int b = Math.min(255, ((x + y) * 255 / 3500) + rnd.nextInt(12));
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void deliveredDerivativeIsABoundedSmallJpegWithTheContentTypeSet() throws Exception {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        byte[] big = largePhotoLikePng();
        String key = "ride-selfies/" + java.util.UUID.randomUUID();
        fileStorage.upload(key, new java.io.ByteArrayInputStream(big), big.length, "image/png");
        RideSelfie selfie = new RideSelfie();
        selfie.setCustomerId(customer.getId());
        selfie.setBookingId(booking.getId());
        selfie.setSelfieStorageKey(key);
        rideSelfies.saveAndFlush(selfie);

        java.net.URI url = service.deliverFor(driver.getUserId(), booking.getId());

        java.util.Map<String, String> q = new java.util.HashMap<>();
        for (String pair : url.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            q.put(kv[0], java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8));
        }
        org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/internal/files")
                                .param("key", q.get("key")).param("exp", q.get("exp")).param("sig", q.get("sig")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType("image/jpeg"))
                .andReturn();
        byte[] delivered = result.getResponse().getContentAsByteArray();
        assertThat(delivered[0] & 0xFF).isEqualTo(0xFF); // JPEG SOI marker
        assertThat(delivered[1] & 0xFF).isEqualTo(0xD8);
        assertThat(delivered.length).isLessThan(300 * 1024);
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(delivered));
        assertThat(decoded).isNotNull();
        assertThat(Math.max(decoded.getWidth(), decoded.getHeight())).isLessThanOrEqualTo(1024);
        assertThat(decoded.getWidth()).isEqualTo(1024); // aspect ratio kept: 2000x1500 -> 1024x768
        assertThat(decoded.getHeight()).isEqualTo(768);
    }
}
