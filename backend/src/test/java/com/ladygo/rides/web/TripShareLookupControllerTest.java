package com.ladygo.rides.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import com.ladygo.rides.service.TripShareService;
import com.ladygo.rides.web.dto.TripShareCreatedResponse;
import com.ladygo.testsupport.PersistenceTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** docs/api/safety.md section 4 - HTTP-layer concerns the service-level test can't see: the token
 *  actually travels via a header (not a path/query param), the response carries no-store cache
 *  headers, and the raw token never appears in anything logged during the request. */
@AutoConfigureMockMvc
class TripShareLookupControllerTest extends PersistenceTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TripShareService tripShareService;

    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void captureAllLogging() {
        logCapture = new ListAppender<>();
        logCapture.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logCapture);
    }

    @AfterEach
    void stopCapturing() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logCapture);
    }

    private City city() {
        return newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
    }

    private TripShareCreatedResponse aLiveShare() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        driver.setCurrentLocation(point(74.81, 34.09));
        driver.setCurrentLocationAt(Instant.now());
        drivers.saveAndFlush(driver);
        newVehicle(driver);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ACCEPTED, driver.getId());
        newRide(booking, driver);
        return tripShareService.create(customer.getUserId(), booking.getId());
    }

    @Test
    void lookupViaTheHeaderSucceedsWithNoAuthenticationAndNoStoreCacheHeaders() throws Exception {
        TripShareCreatedResponse share = aLiveShare();

        mockMvc.perform(get("/api/v1/trip-shares/lookup")
                        .header("X-Trip-Share-Token", share.token()))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Cache-Control"))
                        .containsIgnoringCase("no-store"));
    }

    @Test
    void lookupWithAMissingHeaderIsRejectedTheSameGenericWay() throws Exception {
        mockMvc.perform(get("/api/v1/trip-shares/lookup"))
                .andExpect(status().isNotFound());
    }

    @Test
    void lookupWithAnUnknownTokenViaHttpIsRejectedTheSameGenericWay() throws Exception {
        mockMvc.perform(get("/api/v1/trip-shares/lookup")
                        .header("X-Trip-Share-Token", "totally-made-up"))
                .andExpect(status().isNotFound());
    }

    /** Security requirement: the raw token must never be written to logs. Captures every log event
     *  emitted (at the ROOT logger, every appender this app writes through) during a real HTTP request
     *  carrying the token, including both a successful and a failing lookup, and asserts the exact raw
     *  token string never appears in any captured message. */
    @Test
    void theRawTokenNeverAppearsInAnyLogOutputDuringASuccessfulOrFailingLookup() throws Exception {
        TripShareCreatedResponse share = aLiveShare();

        mockMvc.perform(get("/api/v1/trip-shares/lookup").header("X-Trip-Share-Token", share.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/trip-shares/lookup").header("X-Trip-Share-Token", share.token() + "x"))
                .andExpect(status().isNotFound());

        for (ILoggingEvent event : logCapture.list) {
            if (event.getLevel().isGreaterOrEqual(Level.DEBUG)) {
                assertThat(event.getFormattedMessage()).doesNotContain(share.token());
            }
        }
    }
}
