package com.ladygo.admin.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.ladygo.admin.web.dto.AdminDashboardResponse;
import com.ladygo.admin.web.dto.AdminDashboardResponse.Adoption;
import com.ladygo.admin.web.dto.AdminDashboardResponse.Customers;
import com.ladygo.admin.web.dto.AdminDashboardResponse.Drivers;
import com.ladygo.admin.web.dto.AdminDashboardResponse.LoginAlerts;
import com.ladygo.admin.web.dto.AdminDashboardResponse.Rides;
import com.ladygo.admin.web.dto.AdminDashboardResponse.Safety;
import com.ladygo.admin.web.dto.AdminDashboardResponse.Verification;
import com.ladygo.admin.web.dto.AdminDashboardResponse.WeeklyRides;
import com.ladygo.admin.web.dto.SafetyOpenCountResponse;
import com.ladygo.config.ConfigurationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only dashboard figures (docs/development/plans/2026-10-31-admin-portal-completion.md step 9). Self-contained SQL,
 * one aggregate query per tile group, every one a count over an indexed status or time range; nothing here selects a
 * name, phone, address or position. "Today" is the Asia/Kolkata calendar day and weeks start on Monday there.
 */
@Service
public class AdminDashboardService {

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    static final String REVIEW_TARGET_KEY = "admin.dashboard.review_target.hours";
    static final String SOS_TARGET_KEY = "admin.dashboard.sos_ack_target.minutes";
    static final int DEFAULT_REVIEW_TARGET_HOURS = 24;
    static final int DEFAULT_SOS_TARGET_MINUTES = 5;
    static final int WEEKS = 8;
    private static final Duration SOS_WINDOW = Duration.ofDays(30);

    private final JdbcTemplate jdbc;
    private final ConfigurationService config;
    private final AdminSafetyService safety;

    public AdminDashboardService(JdbcTemplate jdbc, ConfigurationService config, AdminSafetyService safety) {
        this.jdbc = jdbc;
        this.config = config;
        this.safety = safety;
    }

    @Transactional(readOnly = true)
    public AdminDashboardResponse snapshot() {
        return snapshot(Instant.now());
    }

    /** The clock is a parameter so the day and week boundaries can be tested exactly. */
    @Transactional(readOnly = true)
    AdminDashboardResponse snapshot(Instant now) {
        LocalDate today = now.atZone(ZONE).toLocalDate();
        Instant dayStart = today.atStartOfDay(ZONE).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(ZONE).toInstant();
        return new AdminDashboardResponse(now, ZONE.getId(), today,
                drivers(now), rides(now, dayStart, dayEnd), customers(now), verification(now), safety(now),
                adoption(today), loginAlerts(now));
    }

    private Drivers drivers(Instant now) {
        int maxAge = config.getInt(AdminLiveMapService.MAX_AGE_KEY, AdminLiveMapService.DEFAULT_MAX_AGE_MINUTES);
        long[] counts = jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE verification_status = 'APPROVED'),
                       count(*) FILTER (WHERE availability_status = 'ONLINE' AND current_location IS NOT NULL
                                          AND current_location_at >= ?)
                FROM drivers
                """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, ts(now.minusSeconds(maxAge * 60L)));
        return new Drivers(counts[0], counts[1], maxAge);
    }

    private Rides rides(Instant now, Instant dayStart, Instant dayEnd) {
        long[] live = jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status IN ('DRIVER_ASSIGNED', 'DRIVER_ACCEPTED', 'DRIVER_ARRIVING',
                                                         'DRIVER_ARRIVED', 'RIDE_STARTED')),
                       count(*) FILTER (WHERE status IN ('REQUESTED', 'SEARCHING_DRIVER'))
                FROM bookings
                WHERE status IN ('REQUESTED', 'SEARCHING_DRIVER', 'DRIVER_ASSIGNED', 'DRIVER_ACCEPTED',
                                 'DRIVER_ARRIVING', 'DRIVER_ARRIVED', 'RIDE_STARTED')
                """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)});
        long[] done = jdbc.queryForObject("""
                SELECT count(*),
                       coalesce(sum(b.final_fare) FILTER (WHERE b.payment_method = 'CASH'), 0)
                FROM rides r JOIN bookings b ON b.id = r.booking_id
                WHERE r.status = 'COMPLETED' AND r.completed_at >= ? AND r.completed_at < ?
                """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, ts(dayStart), ts(dayEnd));
        // The cancel event is the only timestamp of a cancellation (bookings.updated_at moves on any later touch).
        Long cancelled = jdbc.queryForObject("""
                SELECT count(*) FROM booking_events
                WHERE to_status = 'CANCELLED' AND created_at >= ? AND created_at < ?
                """, Long.class, ts(dayStart), ts(dayEnd));
        return new Rides(live[0], live[1], done[0], cancelled == null ? 0 : cancelled,
                BigDecimal.valueOf(done[1], 2));
    }

    private Customers customers(Instant now) {
        long[] counts = jdbc.queryForObject(
                "SELECT count(*), count(*) FILTER (WHERE created_at >= ?) FROM customers",
                (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, ts(now.minus(Duration.ofDays(7))));
        return new Customers(counts[0], counts[1]);
    }

    private Verification verification(Instant now) {
        int targetHours = config.getInt(REVIEW_TARGET_KEY, DEFAULT_REVIEW_TARGET_HOURS);
        // Waiting since the oldest document still awaiting review; a driver in review with none falls back to the
        // last time the application changed.
        Verification result = jdbc.queryForObject("""
                WITH waiting AS (
                    SELECT coalesce((SELECT min(x.created_at) FROM driver_documents x
                                     WHERE x.driver_id = d.id AND x.status = 'PENDING'), d.updated_at) AS since
                    FROM drivers d WHERE d.verification_status = 'PENDING_REVIEW')
                SELECT count(*),
                       count(*) FILTER (WHERE since < ?),
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM (?::timestamptz - since))),
                       max(extract(epoch FROM (?::timestamptz - since)))
                FROM waiting
                """, (rs, i) -> {
                    long count = rs.getLong(1);
                    return new Verification(count, rs.getLong(2),
                            count == 0 ? null : toUnit(rs.getDouble(3), 3600),
                            count == 0 ? null : toUnit(rs.getDouble(4), 3600), targetHours);
                },
                ts(now.minus(Duration.ofHours(targetHours))), ts(now), ts(now));
        return result;
    }

    private Safety safety(Instant now) {
        int targetMinutes = config.getInt(SOS_TARGET_KEY, DEFAULT_SOS_TARGET_MINUTES);
        SafetyOpenCountResponse open = safety.openCount();
        Double[] median = new Double[1];
        long[] sample = new long[1];
        jdbc.query("""
                SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY secs), count(*) FROM (
                    SELECT extract(epoch FROM (min(a.created_at) - c.created_at)) AS secs
                    FROM safety_incidents i
                    JOIN safety_incident_events c ON c.incident_id = i.id AND c.event_type = 'CREATED'
                    JOIN safety_incident_events a ON a.incident_id = i.id AND a.event_type = 'ACKNOWLEDGED'
                    WHERE i.type = 'SOS' AND i.created_at >= ?
                    GROUP BY i.id, c.created_at) t
                """, rs -> {
                    sample[0] = rs.getLong(2);
                    median[0] = sample[0] == 0 ? null : toUnit(Math.max(0, rs.getDouble(1)), 60);
                }, ts(now.minus(SOS_WINDOW)));
        Double oldest = jdbc.queryForObject("""
                SELECT extract(epoch FROM (?::timestamptz - min(created_at)))
                FROM safety_incidents WHERE type = 'SOS' AND status = 'OPEN'
                """, (rs, i) -> {
                    double secs = rs.getDouble(1);
                    return rs.wasNull() ? null : toUnit(Math.max(0, secs), 60);
                }, ts(now));
        return new Safety(open.unresolved(), open.needsAcknowledging(), open.critical(), median[0], sample[0], oldest,
                targetMinutes);
    }

    private Adoption adoption(LocalDate today) {
        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate firstMonday = thisMonday.minusWeeks(WEEKS - 1);
        long[] captains = jdbc.queryForObject("""
                SELECT count(*),
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM driver_documents x WHERE x.driver_id = d.id
                                                       AND x.type = 'VEHICLE_REGISTRATION')
                                          AND EXISTS (SELECT 1 FROM driver_documents x WHERE x.driver_id = d.id
                                                       AND x.type = 'SELFIE')
                                          AND EXISTS (SELECT 1 FROM vehicles v WHERE v.driver_id = d.id)
                                          AND EXISTS (SELECT 1 FROM self_declarations s
                                                       WHERE s.subject_type = 'DRIVER' AND s.subject_id = d.id)),
                       count(*) FILTER (WHERE d.verification_status = 'APPROVED')
                FROM drivers d
                """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)});
        Long riders = jdbc.queryForObject(
                "SELECT count(DISTINCT customer_id) FROM rides WHERE status = 'COMPLETED'", Long.class);

        Map<LocalDate, Long> perWeek = new HashMap<>();
        jdbc.query("""
                SELECT date_trunc('week', completed_at AT TIME ZONE 'Asia/Kolkata')::date AS week_start, count(*)
                FROM rides WHERE status = 'COMPLETED' AND completed_at >= ? AND completed_at < ?
                GROUP BY 1
                """, rs -> {
                    perWeek.put(rs.getDate(1).toLocalDate(), rs.getLong(2));
                }, ts(firstMonday.atStartOfDay(ZONE).toInstant()),
                ts(thisMonday.plusWeeks(1).atStartOfDay(ZONE).toInstant()));
        List<WeeklyRides> weeks = new ArrayList<>(WEEKS);
        for (int i = 0; i < WEEKS; i++) {
            LocalDate monday = firstMonday.plusWeeks(i);
            weeks.add(new WeeklyRides(monday, perWeek.getOrDefault(monday, 0L)));
        }
        return new Adoption(captains[0], captains[1], captains[2], riders == null ? 0 : riders, weeks);
    }

    private LoginAlerts loginAlerts(Instant now) {
        int threshold = config.getInt("admin.login.alert.failures", 10);
        int windowMinutes = config.getInt("admin.login.alert.window.minutes", 10);
        Long accounts = jdbc.queryForObject(
                "SELECT count(*) FROM admin_login_throttle WHERE window_failures >= ? AND window_start >= ?",
                Long.class, threshold, ts(now.minus(Duration.ofMinutes(windowMinutes))));
        return new LoginAlerts(accounts == null ? 0 : accounts, threshold, windowMinutes);
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    /** Seconds to hours or minutes, one decimal place. */
    private static double toUnit(double seconds, int secondsPerUnit) {
        return BigDecimal.valueOf(seconds / secondsPerUnit).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
