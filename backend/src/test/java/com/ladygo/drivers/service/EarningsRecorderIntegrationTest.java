package com.ladygo.drivers.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.service.BookingStateMachine;
import com.ladygo.bookings.service.BookingStatusChangedEvent;
import com.ladygo.common.domain.ActorType;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.drivers.domain.RideEarning;
import com.ladygo.drivers.repository.RideEarningRepository;
import com.ladygo.locations.domain.City;
import com.ladygo.payments.domain.PaymentMode;
import com.ladygo.rides.domain.Ride;
import com.ladygo.testsupport.RideScenarioSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/** docs/api/driver-earnings-ratings.md sections 4.2, 5, 8: one earnings row per completed ride. */
class EarningsRecorderIntegrationTest extends RideScenarioSupport {

    @Autowired private BookingStateMachine stateMachine;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private RideEarningRepository earnings;

    private record Scenario(Booking booking, Driver driver) { }

    private Scenario startedRide(long finalFare, PaymentMode mode) {
        City city = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        driver.setVerificationStatus(DriverVerificationStatus.APPROVED);
        driver.setAvailabilityStatus(DriverAvailability.BUSY);
        drivers.saveAndFlush(driver);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());
        booking.setPaymentMethod(mode);
        bookings.saveAndFlush(booking);
        UUID d = driver.getId();
        stateMachine.transition(booking.getId(), BookingStatus.DRIVER_ACCEPTED, ActorType.DRIVER, d, "accepted");
        stateMachine.transition(booking.getId(), BookingStatus.DRIVER_ARRIVING, ActorType.DRIVER, d, "x");
        stateMachine.transition(booking.getId(), BookingStatus.DRIVER_ARRIVED, ActorType.DRIVER, d, "x");
        stateMachine.transition(booking.getId(), BookingStatus.RIDE_STARTED, ActorType.DRIVER, d, "x");
        Booking started = bookings.findById(booking.getId()).orElseThrow();
        started.setFinalFare(finalFare);
        bookings.saveAndFlush(started);
        return new Scenario(started, driver);
    }

    private List<RideEarning> earningsFor(Scenario s) {
        flushAndClear();
        Ride ride = rides.findByBookingId(s.booking().getId()).orElseThrow();
        return earnings.findAll().stream().filter(e -> e.getRideId().equals(ride.getId())).toList();
    }

    @Test
    void completingARideWritesExactlyOneEarningsRowWithNoCommissionWhileTheRateIsUnset() {
        Scenario s = startedRide(10_001, PaymentMode.CASH);

        stateMachine.transition(s.booking().getId(), BookingStatus.RIDE_COMPLETED, ActorType.DRIVER, s.driver().getId(), "done");

        List<RideEarning> rows = earningsFor(s);
        assertThat(rows).hasSize(1);
        RideEarning row = rows.get(0);
        assertThat(row.getDriverId()).isEqualTo(s.driver().getId());
        assertThat(row.getGross()).isEqualTo(10_001);
        assertThat(row.getCommissionBps()).isZero();
        assertThat(row.getCommission()).isZero();
        assertThat(row.getNet()).isEqualTo(10_001);
        assertThat(row.getCurrency()).isEqualTo("INR");
        assertThat(row.getPaymentMethod()).isEqualTo(PaymentMode.CASH);
        assertThat(row.getEarnedAt()).isNotNull();
    }

    @Test
    void replayingTheCompletionEventDoesNotAddOrFailAnything() {
        Scenario s = startedRide(5_000, PaymentMode.ONLINE);
        stateMachine.transition(s.booking().getId(), BookingStatus.RIDE_COMPLETED, ActorType.DRIVER, s.driver().getId(), "done");

        eventPublisher.publishEvent(new BookingStatusChangedEvent(
                s.booking().getId(), BookingStatus.RIDE_STARTED, BookingStatus.RIDE_COMPLETED));
        eventPublisher.publishEvent(new BookingStatusChangedEvent(
                s.booking().getId(), BookingStatus.RIDE_STARTED, BookingStatus.RIDE_COMPLETED));

        assertThat(earningsFor(s)).hasSize(1);
        assertThat(earningsFor(s).get(0).getPaymentMethod()).isEqualTo(PaymentMode.ONLINE);
    }

    @Test
    void theConfiguredRateIsSnapshottedAndRoundedHalfUp() {
        setConfig("earnings.commission.bps", "1500");
        Scenario s = startedRide(333, PaymentMode.CASH);

        stateMachine.transition(s.booking().getId(), BookingStatus.RIDE_COMPLETED, ActorType.DRIVER, s.driver().getId(), "done");

        RideEarning row = earningsFor(s).get(0);
        assertThat(row.getCommissionBps()).isEqualTo(1500);
        assertThat(row.getCommission()).isEqualTo(50); // 49.95 rounds up
        assertThat(row.getNet()).isEqualTo(283);

        // Changing the rate later never rewrites history.
        setConfig("earnings.commission.bps", "2000");
        eventPublisher.publishEvent(new BookingStatusChangedEvent(
                s.booking().getId(), BookingStatus.RIDE_STARTED, BookingStatus.RIDE_COMPLETED));
        RideEarning after = earningsFor(s).get(0);
        assertThat(after.getCommissionBps()).isEqualTo(1500);
        assertThat(after.getCommission()).isEqualTo(50);
    }

    @Test
    void aNonNumericRateIsTreatedAsUnset() {
        setConfig("earnings.commission.bps", "not-a-number");
        Scenario s = startedRide(1_000, PaymentMode.CASH);

        stateMachine.transition(s.booking().getId(), BookingStatus.RIDE_COMPLETED, ActorType.DRIVER, s.driver().getId(), "done");

        RideEarning row = earningsFor(s).get(0);
        assertThat(row.getCommission()).isZero();
        assertThat(row.getNet()).isEqualTo(1_000);
    }

    @Test
    void anEarlyEndEarnsNothingInV1() {
        Scenario s = startedRide(2_500, PaymentMode.CASH);

        stateMachine.transition(s.booking().getId(), BookingStatus.RIDE_COMPLETED, ActorType.DRIVER,
                s.driver().getId(), "ride_ended_early: rider felt unwell");

        assertThat(earningsFor(s)).isEmpty();
    }

    @Test
    void aCompletionWithoutAConfirmedFinalFareWritesNothing() {
        Scenario s = startedRide(1_000, PaymentMode.CASH);
        Booking b = bookings.findById(s.booking().getId()).orElseThrow();
        b.setFinalFare(null);
        bookings.saveAndFlush(b);

        stateMachine.transition(s.booking().getId(), BookingStatus.RIDE_COMPLETED, ActorType.DRIVER, s.driver().getId(), "done");

        assertThat(earningsFor(s)).isEmpty();
    }
}
