package com.ladygo.admin.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Counts and timestamps only: no names, phone numbers, addresses or positions (docs/product/admin-flows.md section 2).
 * "Today" is the Asia/Kolkata calendar day; weeks start on Monday in the same zone. Waits and acknowledgement times are
 * null when there is nothing to measure (no pending reviews, no acknowledged SOS yet), never a made-up zero.
 */
public record AdminDashboardResponse(
        Instant generatedAt,
        String timeZone,
        LocalDate today,
        Drivers drivers,
        Rides rides,
        Customers customers,
        Verification verification,
        Safety safety,
        Adoption adoption,
        LoginAlerts loginAlerts) {

    /** {@code active} is APPROVED captains; {@code online} is ONLINE (free to take a ride) with a position newer than {@code onlineMaxAgeMinutes}. */
    public record Drivers(long active, long online, int onlineMaxAgeMinutes) {
    }

    /**
     * {@code active} and {@code pendingBookings} are right now; the rest cover today. {@code cashFaresTodayRupees} is
     * the sum of final fares of today's completed cash rides in rupees: money riders owe captains, not Lady Go revenue.
     */
    public record Rides(long active, long pendingBookings, long completedToday, long cancelledToday,
                        BigDecimal cashFaresTodayRupees) {
    }

    public record Customers(long total, long newLast7Days) {
    }

    /** Applications waiting for review, measured from the same "submitted at" as the verification queue. */
    public record Verification(long waiting, long waitingOverTarget, Double medianWaitHours, Double longestWaitHours,
                               int targetHours) {
    }

    /**
     * The SOS median covers the last 30 days of SOS that were acknowledged ({@code sosAckSample} of them);
     * {@code oldestUnacknowledgedSosMinutes} is how long the longest-waiting unacknowledged SOS has been waiting.
     */
    public record Safety(long unresolved, long needsAcknowledging, long critical, Double sosMedianAckMinutes,
                         long sosAckSample, Double oldestUnacknowledgedSosMinutes, int sosTargetMinutes) {
    }

    /**
     * {@code captainsCompletedAllSteps}: registered with a vehicle, an RC document, a selfie and the signed declaration
     * (whether or not reviewed). {@code weeklyRides} is the last 8 Monday-start weeks, oldest first, zero-filled.
     */
    public record Adoption(long captainsRegistered, long captainsCompletedAllSteps, long captainsApproved,
                           long ridersWhoCompletedARide, List<WeeklyRides> weeklyRides) {
    }

    public record WeeklyRides(LocalDate weekStart, long completed) {
    }

    /**
     * Admin sign-in addresses with at least {@code failureThreshold} failed attempts inside the last
     * {@code windowMinutes} (the same rule as the {@code admin_login_failures_high} log alert). Addresses are never returned.
     */
    public record LoginAlerts(long accountsWithRepeatedFailures, int failureThreshold, int windowMinutes) {
    }
}
