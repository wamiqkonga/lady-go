package com.ladygo.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ladygo.admin.web.dto.AdminDashboardResponse;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverAvailability;
import com.ladygo.locations.domain.City;
import com.ladygo.rides.domain.Ride;
import com.ladygo.testsupport.PersistenceTestSupport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The dashboard numbers against real rows. "Now" is passed in so the Asia/Kolkata day and week boundaries are tested
 * deterministically. Counts are compared before and after adding rows (other committed test data may exist); the
 * median and longest waits first park every foreign row, inside this rolled-back transaction, so they are exact.
 */
class AdminDashboardServiceTest extends PersistenceTestSupport {

    /** 2026-11-04 00:10 in India; still 2026-11-03 in UTC. */
    private static final Instant JUST_AFTER_IST_MIDNIGHT = Instant.parse("2026-11-03T18:40:00Z");

    @Autowired
    private AdminDashboardService service;

    private Ride completedRide(Instant completedAt, long finalFareMinor) {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.COMPLETED, driver.getId());
        jdbc.update("UPDATE bookings SET final_fare = ? WHERE id = ?", finalFareMinor, booking.getId());
        Ride ride = newRide(booking, driver);
        jdbc.update("UPDATE rides SET status = 'COMPLETED', started_at = ?, completed_at = ? WHERE id = ?",
                Timestamp.from(completedAt.minusSeconds(600)), Timestamp.from(completedAt), ride.getId());
        return ride;
    }

    private void cancelledBooking(Instant cancelledAt) {
        Booking booking = newBooking(newCustomer(), newCity(), BookingStatus.CANCELLED, null);
        jdbc.update("INSERT INTO booking_events (booking_id, from_status, to_status, actor_type, created_at) "
                + "VALUES (?, 'SEARCHING_DRIVER', 'CANCELLED', 'CUSTOMER', ?)", booking.getId(), Timestamp.from(cancelledAt));
    }

    private void parkForeignReviewsAndIncidents() {
        jdbc.update("UPDATE drivers SET verification_status = 'NOT_SUBMITTED', availability_status = 'OFFLINE' "
                + "WHERE verification_status = 'PENDING_REVIEW'");
        jdbc.update("UPDATE safety_incidents SET type = 'OTHER'");
    }

    private Driver waitingDriver(Instant submittedAt) {
        Driver driver = newDriver(newCity());
        jdbc.update("UPDATE drivers SET verification_status = 'PENDING_REVIEW' WHERE id = ?", driver.getId());
        jdbc.update("INSERT INTO driver_documents (driver_id, type, storage_key, status, created_at) "
                + "VALUES (?, 'SELFIE', 'k-' || gen_random_uuid(), 'PENDING', ?)",
                driver.getId(), Timestamp.from(submittedAt));
        return driver;
    }

    private UUID sos(String status, Instant createdAt, Instant acknowledgedAt) {
        UUID id = UUID.randomUUID();
        UUID reporter = newCustomer().getUserId();
        jdbc.update("INSERT INTO safety_incidents (id, reporter_id, type, severity, status, created_at) "
                + "VALUES (?, ?, 'SOS', 'CRITICAL', ?, ?)", id, reporter, status, Timestamp.from(createdAt));
        jdbc.update("INSERT INTO safety_incident_events (incident_id, event_type, created_at) VALUES (?, 'CREATED', ?)",
                id, Timestamp.from(createdAt));
        if (acknowledgedAt != null) {
            jdbc.update("INSERT INTO safety_incident_events (incident_id, event_type, created_at) "
                    + "VALUES (?, 'ACKNOWLEDGED', ?)", id, Timestamp.from(acknowledgedAt));
        }
        return id;
    }

    @Test
    void todayIsTheKolkataCalendarDayNotTheUtcDay() {
        AdminDashboardResponse before = service.snapshot(JUST_AFTER_IST_MIDNIGHT);
        assertThat(before.today()).isEqualTo(LocalDate.parse("2026-11-04"));
        assertThat(before.timeZone()).isEqualTo("Asia/Kolkata");

        completedRide(Instant.parse("2026-11-03T18:25:00Z"), 10_000); // 23:55 IST, yesterday
        completedRide(Instant.parse("2026-11-03T18:35:00Z"), 25_050); // 00:05 IST, today
        completedRide(Instant.parse("2026-11-04T18:29:59Z"), 5_000); // 23:59:59 IST, still today, after "now"
        cancelledBooking(Instant.parse("2026-11-03T18:20:00Z")); // yesterday
        cancelledBooking(Instant.parse("2026-11-03T18:31:00Z")); // today

        AdminDashboardResponse after = service.snapshot(JUST_AFTER_IST_MIDNIGHT);
        assertThat(after.rides().completedToday() - before.rides().completedToday()).isEqualTo(2);
        assertThat(after.rides().cancelledToday() - before.rides().cancelledToday()).isEqualTo(1);
        // Fares are minor units on the way in and rupees on the way out: 25050 + 5000 paise = 300.50 rupees.
        assertThat(after.rides().cashFaresTodayRupees().subtract(before.rides().cashFaresTodayRupees()))
                .isEqualByComparingTo(new BigDecimal("300.50"));
    }

    @Test
    void theDayAfterIsExcluded() {
        AdminDashboardResponse before = service.snapshot(JUST_AFTER_IST_MIDNIGHT);
        completedRide(Instant.parse("2026-11-04T18:30:00Z"), 9_900); // 00:00 IST the next day
        AdminDashboardResponse after = service.snapshot(JUST_AFTER_IST_MIDNIGHT);
        assertThat(after.rides().completedToday()).isEqualTo(before.rides().completedToday());
    }

    @Test
    void onlyCashCompletedRidesCountAsCashFares() {
        AdminDashboardResponse before = service.snapshot(JUST_AFTER_IST_MIDNIGHT);
        Ride ride = completedRide(Instant.parse("2026-11-03T19:00:00Z"), 40_000);
        jdbc.update("UPDATE bookings SET payment_method = 'ONLINE' WHERE id = ?", ride.getBookingId());
        AdminDashboardResponse after = service.snapshot(JUST_AFTER_IST_MIDNIGHT);
        assertThat(after.rides().completedToday() - before.rides().completedToday()).isEqualTo(1);
        assertThat(after.rides().cashFaresTodayRupees()).isEqualByComparingTo(before.rides().cashFaresTodayRupees());
    }

    @Test
    void weeklyRidesAreEightMondayWeeksInKolkataAndZeroFilled() {
        Instant tuesdayMorning = Instant.parse("2026-11-03T04:30:00Z"); // Tue 10:00 IST
        AdminDashboardResponse before = service.snapshot(tuesdayMorning);
        assertThat(before.adoption().weeklyRides()).hasSize(8);
        assertThat(before.adoption().weeklyRides().get(7).weekStart()).isEqualTo(LocalDate.parse("2026-11-02"));
        assertThat(before.adoption().weeklyRides().get(0).weekStart()).isEqualTo(LocalDate.parse("2026-09-14"));

        completedRide(Instant.parse("2026-11-01T19:00:00Z"), 100); // Mon 00:30 IST: this week (Sunday in UTC)
        completedRide(Instant.parse("2026-11-01T18:00:00Z"), 100); // Sun 23:30 IST: the week before
        completedRide(Instant.parse("2026-09-13T18:29:00Z"), 100); // Sun 23:59 IST: older than 8 weeks, not shown

        AdminDashboardResponse after = service.snapshot(tuesdayMorning);
        assertThat(after.adoption().weeklyRides().get(7).completed() - before.adoption().weeklyRides().get(7).completed())
                .isEqualTo(1);
        assertThat(after.adoption().weeklyRides().get(6).completed() - before.adoption().weeklyRides().get(6).completed())
                .isEqualTo(1);
        assertThat(after.adoption().weeklyRides().get(0).completed()).isEqualTo(before.adoption().weeklyRides().get(0).completed());
    }

    @Test
    void onlyApprovedFreshOnlineDriversAreOnline() {
        Instant now = Instant.now();
        AdminDashboardResponse before = service.snapshot(now);
        City city = newCity();
        newOnlineDriver(city, 74.80, 34.08); // counts: approved, ONLINE, fresh
        Driver stale = newOnlineDriver(city, 74.80, 34.08);
        jdbc.update("UPDATE drivers SET current_location_at = ? WHERE id = ?",
                Timestamp.from(now.minus(Duration.ofMinutes(45))), stale.getId());
        Driver busy = newOnlineDriver(city, 74.80, 34.08);
        jdbc.update("UPDATE drivers SET availability_status = ? WHERE id = ?",
                DriverAvailability.ON_RIDE.name(), busy.getId());

        AdminDashboardResponse after = service.snapshot(now);
        assertThat(after.drivers().active() - before.drivers().active()).isEqualTo(3);
        assertThat(after.drivers().online() - before.drivers().online()).isEqualTo(1);
    }

    @Test
    void activeRidesPendingBookingsAndCustomers() {
        AdminDashboardResponse before = service.snapshot(Instant.now());
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId());
        newBooking(customer, city, BookingStatus.SEARCHING_DRIVER, null);
        newBooking(customer, city, BookingStatus.REQUESTED, null);
        newBooking(customer, city, BookingStatus.COMPLETED, null);
        Customer old = newCustomer();
        jdbc.update("UPDATE customers SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(30))), old.getId());

        AdminDashboardResponse after = service.snapshot(Instant.now());
        assertThat(after.rides().active() - before.rides().active()).isEqualTo(1);
        assertThat(after.rides().pendingBookings() - before.rides().pendingBookings()).isEqualTo(2);
        assertThat(after.customers().total() - before.customers().total()).isEqualTo(2);
        assertThat(after.customers().newLast7Days() - before.customers().newLast7Days()).isEqualTo(1);
    }

    @Test
    void reviewWaitsUseTheQueuesSubmittedAtAndItsOverdueTarget() {
        parkForeignReviewsAndIncidents();
        Instant now = Instant.parse("2026-11-03T10:00:00Z");
        waitingDriver(now.minus(Duration.ofHours(2)));
        waitingDriver(now.minus(Duration.ofHours(10)));
        waitingDriver(now.minus(Duration.ofHours(30)));

        AdminDashboardResponse.Verification verification = service.snapshot(now).verification();
        assertThat(verification.waiting()).isEqualTo(3);
        assertThat(verification.waitingOverTarget()).isEqualTo(1);
        assertThat(verification.medianWaitHours()).isEqualTo(10.0);
        assertThat(verification.longestWaitHours()).isEqualTo(30.0);
        assertThat(verification.targetHours()).isEqualTo(24);

        jdbc.update("UPDATE configuration SET value = '8' WHERE key = 'verification.queue.overdue.hours'");
        flushAndClear(); // the first snapshot loaded the old config row into the persistence context
        verification = service.snapshot(now).verification();
        assertThat(verification.targetHours()).isEqualTo(8);
        assertThat(verification.waitingOverTarget()).isEqualTo(2);
    }

    @Test
    void aCaptainWithNothingUploadedWaitsFromAccountCreationLikeTheQueue() {
        parkForeignReviewsAndIncidents();
        Driver driver = newDriver(newCity());
        jdbc.update("UPDATE drivers SET verification_status = 'PENDING_REVIEW', created_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-11-02T04:00:00Z")), driver.getId());
        AdminDashboardResponse.Verification verification = service.snapshot(Instant.parse("2026-11-03T10:00:00Z")).verification();
        assertThat(verification.waiting()).isEqualTo(1);
        assertThat(verification.longestWaitHours()).isEqualTo(30.0);
        assertThat(verification.waitingOverTarget()).isEqualTo(1);
    }

    @Test
    void noPendingReviewsGivesNullWaitsNotZero() {
        parkForeignReviewsAndIncidents();
        AdminDashboardResponse.Verification verification = service.snapshot(Instant.now()).verification();
        assertThat(verification.waiting()).isZero();
        assertThat(verification.medianWaitHours()).isNull();
        assertThat(verification.longestWaitHours()).isNull();
    }

    @Test
    void sosAcknowledgementTimesComeFromTheTimelineEvents() {
        parkForeignReviewsAndIncidents();
        Instant now = Instant.parse("2026-11-03T10:00:00Z");
        sos("ACKNOWLEDGED", now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(3)).plusSeconds(60)); // 1 min
        sos("RESOLVED", now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(2)).plusSeconds(180)); // 3 min
        sos("ACKNOWLEDGED", now.minus(Duration.ofHours(1)), now.minus(Duration.ofHours(1)).plusSeconds(600)); // 10 min
        sos("OPEN", now.minus(Duration.ofMinutes(7)), null); // nobody has looked yet

        AdminDashboardResponse.Safety safety = service.snapshot(now).safety();
        assertThat(safety.sosMedianAckMinutes()).isEqualTo(3.0);
        assertThat(safety.sosAckSample()).isEqualTo(3);
        assertThat(safety.oldestUnacknowledgedSosMinutes()).isEqualTo(7.0);
        assertThat(safety.sosTargetMinutes()).isEqualTo(5);
        assertThat(safety.needsAcknowledging()).isGreaterThanOrEqualTo(1);
        assertThat(safety.critical()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void acknowledgedSosDoesNotCountAsWaiting() {
        parkForeignReviewsAndIncidents();
        Instant now = Instant.parse("2026-11-03T10:00:00Z");
        sos("ACKNOWLEDGED", now.minus(Duration.ofMinutes(30)), now.minus(Duration.ofMinutes(29)));
        AdminDashboardResponse.Safety safety = service.snapshot(now).safety();
        assertThat(safety.oldestUnacknowledgedSosMinutes()).isNull();
        assertThat(safety.sosMedianAckMinutes()).isEqualTo(1.0);
    }

    @Test
    void adoptionCountsCaptainsByHowFarTheyGot() {
        AdminDashboardResponse before = service.snapshot(Instant.now());
        City city = newCity();
        Driver started = newDriver(city); // registered only
        Driver done = newDriver(city); // every required step, not yet approved
        jdbc.update("INSERT INTO driver_documents (driver_id, type, storage_key) VALUES (?, 'SELFIE', 'k1')", done.getId());
        jdbc.update("INSERT INTO driver_documents (driver_id, type, storage_key) VALUES (?, 'VEHICLE_REGISTRATION', 'k2')", done.getId());
        newVehicle(done);
        acceptDriverDeclaration(done);
        Driver approved = newOnlineDriver(city, 74.8, 34.08);
        assertThat(started.getId()).isNotNull();

        AdminDashboardResponse.Adoption a = service.snapshot(Instant.now()).adoption();
        assertThat(a.captainsRegistered() - before.adoption().captainsRegistered()).isEqualTo(3);
        assertThat(a.captainsCompletedAllSteps() - before.adoption().captainsCompletedAllSteps()).isEqualTo(1);
        assertThat(a.captainsApproved() - before.adoption().captainsApproved()).isEqualTo(1);
        assertThat(approved.getId()).isNotNull();
    }

    @Test
    void ridersWhoCompletedARideAreCountedOnceEach() {
        AdminDashboardResponse before = service.snapshot(Instant.now());
        Ride first = completedRide(Instant.now().minusSeconds(3600), 100);
        Customer rider = customers.findById(first.getCustomerId()).orElseThrow();
        City city = newCity();
        Driver driver = newDriver(city);
        Ride second = newRide(newBooking(rider, city, BookingStatus.COMPLETED, driver.getId()), driver);
        jdbc.update("UPDATE rides SET status = 'COMPLETED', completed_at = now() WHERE id = ?", second.getId());
        newBooking(newCustomer(), city, BookingStatus.REQUESTED, null); // a rider who never finished a ride

        AdminDashboardResponse after = service.snapshot(Instant.now());
        assertThat(after.adoption().ridersWhoCompletedARide() - before.adoption().ridersWhoCompletedARide()).isEqualTo(1);
    }

    @Test
    void loginAlertsCountEmailsOverTheFailureThresholdWithoutNamingThem() {
        AdminDashboardResponse before = service.snapshot(Instant.now());
        jdbc.update("INSERT INTO admin_login_throttle (email, failure_count, window_start, window_failures, "
                + "last_failure_at, locked_until) VALUES ('hot@example.test', 12, now(), 12, now(), now())");
        jdbc.update("INSERT INTO admin_login_throttle (email, failure_count, window_start, window_failures, "
                + "last_failure_at, locked_until) VALUES ('cool@example.test', 2, now(), 2, now(), now())");
        jdbc.update("INSERT INTO admin_login_throttle (email, failure_count, window_start, window_failures, "
                + "last_failure_at, locked_until) VALUES ('old@example.test', 50, now() - interval '3 hours', 50, "
                + "now() - interval '3 hours', now())");

        AdminDashboardResponse after = service.snapshot(Instant.now());
        assertThat(after.loginAlerts().accountsWithRepeatedFailures()
                - before.loginAlerts().accountsWithRepeatedFailures()).isEqualTo(1);
        assertThat(after.loginAlerts().failureThreshold()).isEqualTo(10);
        assertThat(after.loginAlerts().windowMinutes()).isEqualTo(10);
    }

    private void acceptDriverDeclaration(Driver driver) {
        acceptCurrentDeclaration(com.ladygo.verification.domain.SubjectType.DRIVER, driver.getId());
    }
}
