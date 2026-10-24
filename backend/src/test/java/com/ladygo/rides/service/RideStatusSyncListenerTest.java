package com.ladygo.rides.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.bookings.service.BookingStateMachine;
import com.ladygo.bookings.service.BookingStatusChangedEvent;
import com.ladygo.common.domain.ActorType;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.locations.domain.City;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.domain.RideStatus;
import com.ladygo.testsupport.PersistenceTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Root-cause fix, per the trip-share security review: {@code Ride.status}/{@code driver_id} and the
 * driver's {@code availabilityStatus} must stay consistent with every {@code Booking} transition that
 * touches a ride, not just the two paths ({@code RIDE_STARTED}, {@code RIDE_COMPLETED}) that happened
 * to already have a direct writer. One test per transition that can affect a ride, driven through the
 * real {@link BookingStateMachine} (not direct entity manipulation), so these prove the LISTENER's
 * reaction, not just hand-set test data.
 */
class RideStatusSyncListenerTest extends PersistenceTestSupport {

    @Autowired
    private BookingStateMachine stateMachine;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private City city() {
        return newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
    }

    private Driver busyDriver(City city) {
        Driver driver = newDriver(city);
        driver.setVerificationStatus(DriverVerificationStatus.APPROVED);
        driver.setAvailabilityStatus(DriverAvailability.BUSY);
        return drivers.saveAndFlush(driver);
    }

    @Test
    void acceptingABookingCreatesTheRideRowAssignedToTheDriver() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = busyDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());

        stateMachine.transition(booking.getId(), BookingStatus.DRIVER_ACCEPTED, ActorType.DRIVER, driver.getId(), "accepted");

        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();
        assertThat(ride.getStatus()).isEqualTo(RideStatus.ASSIGNED);
        assertThat(ride.getDriverId()).isEqualTo(driver.getId());
    }

    /** The core security-review finding: nothing used to set Ride.status to CANCELLED at all. */
    @Test
    void cancellingAfterAcceptanceMarksTheRideCancelledAndReleasesTheDriver() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = busyDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());
        stateMachine.transition(booking.getId(), BookingStatus.DRIVER_ACCEPTED, ActorType.DRIVER, driver.getId(), "accepted");

        stateMachine.transition(booking.getId(), BookingStatus.CANCELLED, ActorType.CUSTOMER, customer.getId(), "changed my mind");

        flushAndClear(); // the bulk-update release never refreshed these already-loaded entities.
        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();
        assertThat(ride.getStatus()).isEqualTo(RideStatus.CANCELLED);
        assertThat(ride.getCompletedAt()).isNotNull();
        assertThat(drivers.findById(driver.getId()).orElseThrow().getAvailabilityStatus())
                .isEqualTo(DriverAvailability.ONLINE);
    }

    /** Same as above, via DRIVER_ARRIVING instead of straight from DRIVER_ACCEPTED - a different `from`
     *  state, same `to`, proving the listener reacts to the target status, not a specific transition edge. */
    @Test
    void cancellingWhileArrivingAlsoMarksTheRideCancelledAndReleasesTheDriver() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = busyDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());
        stateMachine.transition(booking.getId(), BookingStatus.DRIVER_ACCEPTED, ActorType.DRIVER, driver.getId(), "accepted");
        stateMachine.transition(booking.getId(), BookingStatus.DRIVER_ARRIVING, ActorType.DRIVER, driver.getId(), "arriving");

        stateMachine.transition(booking.getId(), BookingStatus.CANCELLED, ActorType.CUSTOMER, customer.getId(), "x");

        flushAndClear(); // the bulk-update release never refreshed these already-loaded entities.
        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();
        assertThat(ride.getStatus()).isEqualTo(RideStatus.CANCELLED);
        assertThat(drivers.findById(driver.getId()).orElseThrow().getAvailabilityStatus())
                .isEqualTo(DriverAvailability.ONLINE);
    }

    /** Currently unreachable by any real caller (no code transitions DRIVER_ACCEPTED -> SEARCHING_DRIVER
     *  today), but allowed by the state machine - proving the listener handles it correctly anyway,
     *  rather than leaving a second drift trap for whenever that path is wired up. */
    @Test
    void bouncingBackToSearchingDriverMarksTheOldRideCancelledAndReleasesTheDriver() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = busyDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());
        stateMachine.transition(booking.getId(), BookingStatus.DRIVER_ACCEPTED, ActorType.DRIVER, driver.getId(), "accepted");

        stateMachine.transition(booking.getId(), BookingStatus.SEARCHING_DRIVER, ActorType.SYSTEM, null, "driver backed out");

        flushAndClear(); // the bulk-update release never refreshed these already-loaded entities.
        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();
        assertThat(ride.getStatus()).isEqualTo(RideStatus.CANCELLED);
        assertThat(drivers.findById(driver.getId()).orElseThrow().getAvailabilityStatus())
                .isEqualTo(DriverAvailability.ONLINE);
    }

    /** The full bounce-back-and-reassign loop: rides.booking_id is UNIQUE (V7), so re-acceptance by a
     *  DIFFERENT driver must UPDATE the same row, never create a second one. */
    @Test
    void reAcceptingAfterABounceBackRevivesTheSameRideRowForTheNewDriver() {
        City city = city();
        Customer customer = newCustomer();
        Driver driverA = busyDriver(city);
        Driver driverB = busyDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driverA.getId());
        stateMachine.transition(booking.getId(), BookingStatus.DRIVER_ACCEPTED, ActorType.DRIVER, driverA.getId(), "accepted");
        Ride afterFirstAccept = rides.findByBookingId(booking.getId()).orElseThrow();

        stateMachine.transition(booking.getId(), BookingStatus.SEARCHING_DRIVER, ActorType.SYSTEM, null, "driver backed out");
        Booking reloaded = bookings.findById(booking.getId()).orElseThrow();
        reloaded.setDriverId(driverB.getId());
        bookings.saveAndFlush(reloaded);
        stateMachine.transition(booking.getId(), BookingStatus.DRIVER_ASSIGNED, ActorType.SYSTEM, null, "offered");
        stateMachine.transition(booking.getId(), BookingStatus.DRIVER_ACCEPTED, ActorType.DRIVER, driverB.getId(), "accepted");

        Ride revived = rides.findByBookingId(booking.getId()).orElseThrow();
        assertThat(revived.getId()).isEqualTo(afterFirstAccept.getId()); // same row, not a new one.
        assertThat(revived.getDriverId()).isEqualTo(driverB.getId());
        assertThat(revived.getStatus()).isEqualTo(RideStatus.ASSIGNED);
        assertThat(revived.getCompletedAt()).isNull(); // the earlier abandonment's timestamp was cleared.
    }

    /** The ride itself concluding (RIDE_COMPLETED) must release the driver too - this was the other
     *  half of the same audit: releaseFromOffer was only ever called from offer-reject/expire, never
     *  from ride completion, so every driver got stuck BUSY forever after their first ride. */
    @Test
    void completingTheRideReleasesTheDriverBackOnline() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = busyDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());
        stateMachine.transition(booking.getId(), BookingStatus.DRIVER_ACCEPTED, ActorType.DRIVER, driver.getId(), "accepted");
        stateMachine.transition(booking.getId(), BookingStatus.DRIVER_ARRIVING, ActorType.DRIVER, driver.getId(), "x");
        stateMachine.transition(booking.getId(), BookingStatus.DRIVER_ARRIVED, ActorType.DRIVER, driver.getId(), "x");
        stateMachine.transition(booking.getId(), BookingStatus.RIDE_STARTED, ActorType.DRIVER, driver.getId(), "x");
        // Driver goes BUSY->BUSY across the above (never actually released) - confirm the precondition.
        assertThat(drivers.findById(driver.getId()).orElseThrow().getAvailabilityStatus())
                .isEqualTo(DriverAvailability.BUSY);

        stateMachine.transition(booking.getId(), BookingStatus.RIDE_COMPLETED, ActorType.DRIVER, driver.getId(), "x");

        flushAndClear(); // the bulk-update release never refreshed these already-loaded entities.
        assertThat(drivers.findById(driver.getId()).orElseThrow().getAvailabilityStatus())
                .isEqualTo(DriverAvailability.ONLINE);
    }

    /** Defensive: a COMPLETED ride must never be revived even if DRIVER_ACCEPTED somehow fired again
     *  (should be unreachable via the real state machine - COMPLETED/CANCELLED bookings have no path
     *  back to DRIVER_ACCEPTED - but the listener's own guard is what actually prevents it). */
    @Test
    void aCompletedRideIsNeverRevivedByALaterAcceptedEvent() {
        City city = city();
        Customer customer = newCustomer();
        Driver driver = busyDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.DRIVER_ASSIGNED, driver.getId());
        stateMachine.transition(booking.getId(), BookingStatus.DRIVER_ACCEPTED, ActorType.DRIVER, driver.getId(), "accepted");
        Ride ride = rides.findByBookingId(booking.getId()).orElseThrow();
        ride.setStatus(RideStatus.COMPLETED);
        ride.setCompletedAt(Instant.now());
        rides.saveAndFlush(ride);

        // Fire the event directly (bypassing the state machine's own transition table, which would
        // reject this) purely to prove the LISTENER's own defensive guard, independent of the state
        // machine's guard.
        eventPublisher.publishEvent(new BookingStatusChangedEvent(
                booking.getId(), BookingStatus.COMPLETED, BookingStatus.DRIVER_ACCEPTED));

        Ride reloaded = rides.findByBookingId(booking.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RideStatus.COMPLETED);
    }
}
