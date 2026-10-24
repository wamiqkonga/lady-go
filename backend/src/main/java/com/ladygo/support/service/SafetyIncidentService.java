package com.ladygo.support.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ladygo.support.domain.IncidentSeverity;
import com.ladygo.support.domain.IncidentStatus;
import com.ladygo.support.domain.IncidentType;
import org.locationtech.jts.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Creates safety incidents on behalf of a flow that must never fail because of them (docs/api/driver-earnings-ratings.md
 * section 6, B5: a driver's early end with {@code safetyConcern}). The rows (incident, its CREATED timeline
 * event, and the audit-log entry - the same {@code audit_logs} table every admin action uses) are written
 * on the caller's transaction connection inside a JDBC savepoint, so a database problem rolls back only
 * the incident, never the early end itself; the failure is logged with ids only (no free text). Plain SQL
 * rather than JPA on purpose: a failed JPA flush would leave the caller's persistence context unusable,
 * and {@code JpaTransactionManager} does not support nested transactions.
 */
@Service
public class SafetyIncidentService {

    private static final Logger log = LoggerFactory.getLogger(SafetyIncidentService.class);

    static final int MAX_NOTES_LENGTH = 500;
    static final String AUDIT_ACTION = "SAFETY_INCIDENT_CREATED";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SafetyIncidentService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * One OPEN, HIGH REPORT_CUSTOMER incident per (ride, reporter): a repeat is a no-op. Never throws.
     * Must be called inside the caller's transaction.
     *
     * @param location the driver's last known location, or null if none is on file
     */
    public void reportCustomerFromDriverEarlyEnd(UUID rideId, UUID driverUserId, String reason, Point location) {
        try {
            jdbc.execute((java.sql.Connection con) -> {
                Savepoint savepoint = con.setSavepoint();
                try {
                    insertIfAbsent(con, rideId, driverUserId, reason, location);
                    con.releaseSavepoint(savepoint);
                } catch (java.sql.SQLException | RuntimeException ex) {
                    con.rollback(savepoint);
                    throw ex;
                }
                return null;
            });
        } catch (Exception ex) {
            log.error("operation=safety_incident_create_failed rideId={} reporterId={} errorType={}",
                    rideId, driverUserId, ex.getClass().getSimpleName());
        }
    }

    private void insertIfAbsent(java.sql.Connection con, UUID rideId, UUID driverUserId, String reason, Point location)
            throws java.sql.SQLException {
        try (PreparedStatement exists = con.prepareStatement(
                "SELECT 1 FROM safety_incidents WHERE ride_id = ? AND reporter_id = ? AND type = ?")) {
            exists.setObject(1, rideId);
            exists.setObject(2, driverUserId);
            exists.setString(3, IncidentType.REPORT_CUSTOMER.name());
            try (ResultSet rs = exists.executeQuery()) {
                if (rs.next()) {
                    return;
                }
            }
        }
        UUID incidentId;
        try (PreparedStatement insert = con.prepareStatement(
                "INSERT INTO safety_incidents (ride_id, reporter_id, type, severity, status, location, notes) "
                        + "VALUES (?, ?, ?, ?, ?, "
                        + "CASE WHEN CAST(? AS double precision) IS NULL THEN NULL ELSE "
                        + "CAST(ST_SetSRID(ST_MakePoint(CAST(? AS double precision), CAST(? AS double precision)), 4326) "
                        + "AS geography) END, ?) RETURNING id")) {
            insert.setObject(1, rideId);
            insert.setObject(2, driverUserId);
            insert.setString(3, IncidentType.REPORT_CUSTOMER.name());
            insert.setString(4, IncidentSeverity.HIGH.name());
            insert.setString(5, IncidentStatus.OPEN.name());
            Double lon = location == null ? null : location.getX();
            Double lat = location == null ? null : location.getY();
            setDouble(insert, 6, lon);
            setDouble(insert, 7, lon);
            setDouble(insert, 8, lat);
            insert.setString(9, limit(reason));
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                incidentId = rs.getObject(1, UUID.class);
            }
        }
        try (PreparedStatement event = con.prepareStatement(
                "INSERT INTO safety_incident_events (incident_id, actor_id, event_type) VALUES (?, ?, 'CREATED')")) {
            event.setObject(1, incidentId);
            event.setObject(2, driverUserId);
            event.executeUpdate();
        }
        String metadata = metadataJson(rideId);
        try (PreparedStatement audit = con.prepareStatement(
                "INSERT INTO audit_logs (actor_id, actor_role, action, target_type, target_id, metadata) "
                        + "VALUES (?, 'DRIVER', ?, 'safety_incidents', ?, CAST(? AS jsonb))")) {
            audit.setObject(1, driverUserId);
            audit.setString(2, AUDIT_ACTION);
            audit.setString(3, incidentId.toString());
            audit.setString(4, metadata);
            audit.executeUpdate();
        }
    }

    private String metadataJson(UUID rideId) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "rideId", rideId.toString(), "incidentType", IncidentType.REPORT_CUSTOMER.name(),
                    "severity", IncidentSeverity.HIGH.name(), "source", "DRIVER_EARLY_END"));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void setDouble(PreparedStatement statement, int index, Double value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.DOUBLE);
        } else {
            statement.setDouble(index, value);
        }
    }

    private static String limit(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_NOTES_LENGTH ? text : text.substring(0, MAX_NOTES_LENGTH);
    }
}
