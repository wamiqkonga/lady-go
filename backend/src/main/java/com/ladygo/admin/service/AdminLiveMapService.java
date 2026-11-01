package com.ladygo.admin.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import com.ladygo.admin.web.dto.AdminLiveMapResponse;
import com.ladygo.admin.web.dto.AdminLiveMapResponse.LiveDriver;
import com.ladygo.admin.web.dto.AdminLiveMapResponse.LiveIncident;
import com.ladygo.admin.web.dto.AdminLiveMapResponse.LiveRide;
import com.ladygo.config.ConfigurationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only admin live map. Self-contained SQL on purpose: it reads only {@code drivers.current_location}
 * (the single last-known point, never {@code location_history}), active bookings and unresolved
 * {@code safety_incidents}. Every query is LIMITed (one extra row is fetched to set the truncated flag).
 */
@Service
public class AdminLiveMapService {

    static final String MAX_AGE_KEY = "admin.livemap.max_age.minutes";
    static final int DEFAULT_MAX_AGE_MINUTES = 10;
    static final int LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final ConfigurationService config;

    public AdminLiveMapService(JdbcTemplate jdbc, ConfigurationService config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    @Transactional(readOnly = true)
    public AdminLiveMapResponse snapshot() {
        int maxAgeMinutes = config.getInt(MAX_AGE_KEY, DEFAULT_MAX_AGE_MINUTES);
        Instant now = Instant.now();
        Timestamp oldest = Timestamp.from(now.minusSeconds(maxAgeMinutes * 60L));

        List<LiveDriver> drivers = jdbc.query("""
                SELECT d.id, u.name, d.availability_status,
                       ST_Y(d.current_location::geometry) AS lat, ST_X(d.current_location::geometry) AS lon,
                       d.current_location_at
                FROM drivers d JOIN users u ON u.id = d.user_id
                WHERE d.availability_status IN ('ONLINE', 'BUSY', 'ON_RIDE')
                  AND d.current_location IS NOT NULL
                  AND d.current_location_at >= ?
                ORDER BY d.current_location_at DESC, d.id
                LIMIT ?
                """,
                (rs, i) -> {
                    Instant at = rs.getTimestamp("current_location_at").toInstant();
                    return new LiveDriver(rs.getObject("id", java.util.UUID.class), maskName(rs.getString("name")),
                            rs.getString("availability_status"), rs.getDouble("lat"), rs.getDouble("lon"), at,
                            Math.max(0, now.getEpochSecond() - at.getEpochSecond()));
                },
                oldest, LIMIT + 1);

        List<LiveRide> rides = jdbc.query("""
                SELECT b.id, b.status, b.driver_id, du.name AS driver_name, cu.name AS customer_name,
                       b.pickup_address, ST_Y(b.pickup_location::geometry) AS plat, ST_X(b.pickup_location::geometry) AS plon,
                       b.destination_address, ST_Y(b.destination_location::geometry) AS dlat,
                       ST_X(b.destination_location::geometry) AS dlon
                FROM bookings b
                JOIN customers c ON c.id = b.customer_id
                JOIN users cu ON cu.id = c.user_id
                LEFT JOIN drivers d ON d.id = b.driver_id
                LEFT JOIN users du ON du.id = d.user_id
                WHERE b.status IN ('DRIVER_ASSIGNED', 'DRIVER_ACCEPTED', 'DRIVER_ARRIVING', 'DRIVER_ARRIVED', 'RIDE_STARTED')
                ORDER BY b.updated_at DESC, b.id
                LIMIT ?
                """,
                (rs, i) -> new LiveRide(rs.getObject("id", java.util.UUID.class), rs.getString("status"),
                        rs.getObject("driver_id", java.util.UUID.class), maskName(rs.getString("driver_name")),
                        maskName(rs.getString("customer_name")), rs.getString("pickup_address"),
                        rs.getDouble("plat"), rs.getDouble("plon"), rs.getString("destination_address"),
                        rs.getDouble("dlat"), rs.getDouble("dlon")),
                LIMIT + 1);

        // Unresolved incidents stay on the map however old; the position is where it was raised, not a
        // live track, so the freshness bound does not apply to it.
        List<LiveIncident> incidents = jdbc.query("""
                SELECT id, ride_id, type, severity, status, created_at,
                       ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon, location IS NULL AS no_location
                FROM safety_incidents
                WHERE status IN ('OPEN', 'ACKNOWLEDGED', 'ESCALATED')
                ORDER BY created_at DESC, id
                LIMIT ?
                """,
                (rs, i) -> {
                    boolean none = rs.getBoolean("no_location");
                    return new LiveIncident(rs.getObject("id", java.util.UUID.class),
                            rs.getObject("ride_id", java.util.UUID.class), rs.getString("type"),
                            rs.getString("severity"), rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant(),
                            none ? null : rs.getDouble("lat"), none ? null : rs.getDouble("lon"));
                },
                LIMIT + 1);

        return new AdminLiveMapResponse(now, maxAgeMinutes, LIMIT,
                drivers.size() > LIMIT, rides.size() > LIMIT, incidents.size() > LIMIT,
                cap(drivers), cap(rides), cap(incidents));
    }

    private static <T> List<T> cap(List<T> rows) {
        return rows.size() > LIMIT ? List.copyOf(rows.subList(0, LIMIT)) : rows;
    }

    /** First name plus last initial ("Aisha Khan" becomes "Aisha K."); a single word stays as is. */
    static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        return parts[0] + " " + parts[parts.length - 1].substring(0, 1).toUpperCase() + ".";
    }
}
