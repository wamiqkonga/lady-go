package com.ladygo.admin.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The captain queue: one row per captain in a given verification status, oldest first-upload first. Paged in SQL
 * (LIMIT/OFFSET) so a big backlog never loads whole; the per-row item counts are worked out by the service for just
 * the captains on the page.
 */
@Repository
public class AdminVerificationQueueRepository {

    /** Filters, already normalised by the service. {@code idMatch} is the nil UUID when the term is not an id. */
    public record Filter(DriverVerificationStatus status, boolean matchAll, UUID idMatch, String namePattern,
            boolean digitsOn, String digitsPattern, boolean overdueOnly, Instant overdueBefore, boolean readyOnly,
            String declarationVersion, boolean policeRequired) {
    }

    public record QueueRow(UUID driverId, String name, String phone, String city, DriverVerificationStatus status,
            Instant submittedAt) {
    }

    private static final String FROM = """
            FROM drivers d
            JOIN users u ON u.id = d.user_id
            LEFT JOIN cities c ON c.id = d.city_id
            LEFT JOIN LATERAL (
                SELECT LEAST(
                    (SELECT min(x.created_at) FROM driver_documents x WHERE x.driver_id = d.id),
                    (SELECT min(v.created_at) FROM vehicles v WHERE v.driver_id = d.id),
                    (SELECT min(sd.accepted_at) FROM self_declarations sd
                      WHERE sd.subject_type = 'DRIVER' AND sd.subject_id = d.id)) AS first_at) s ON true
            WHERE d.verification_status = :status
              AND (:matchAll = true OR d.id = :idMatch
                   OR lower(coalesce(u.name, '')) LIKE :namePattern ESCAPE '\\'
                   OR (:digitsOn = true AND regexp_replace(u.phone, '\\D', '', 'g') LIKE :digitsPattern))
              AND (:overdueOnly = false OR coalesce(s.first_at, d.created_at) < CAST(:overdueBefore AS timestamptz))
              AND (:readyOnly = false OR (
                    btrim(coalesce(u.name, '')) <> ''
                    AND EXISTS (SELECT 1 FROM vehicles v2 WHERE v2.driver_id = d.id)
                    AND EXISTS (SELECT 1 FROM driver_documents r WHERE r.driver_id = d.id AND r.type = 'VEHICLE_REGISTRATION')
                    AND EXISTS (SELECT 1 FROM driver_documents f WHERE f.driver_id = d.id AND f.type = 'SELFIE')
                    AND EXISTS (SELECT 1 FROM self_declarations a WHERE a.subject_type = 'DRIVER'
                                AND a.subject_id = d.id AND a.declaration_version = :declarationVersion)
                    AND (:policeRequired = false OR EXISTS (
                            SELECT 1 FROM driver_documents p WHERE p.driver_id = d.id AND p.type = 'POLICE_VERIFICATION'))))
            """;

    private static final RowMapper<QueueRow> MAPPER = (ResultSet rs, int i) -> mapRow(rs);

    private final NamedParameterJdbcTemplate jdbc;

    public AdminVerificationQueueRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<QueueRow> page(Filter filter, int limit, long offset) {
        return jdbc.query("""
                SELECT d.id AS driver_id, u.name AS name, u.phone AS phone, c.name AS city,
                       d.verification_status AS status, coalesce(s.first_at, d.created_at) AS submitted_at
                """ + FROM + """
                ORDER BY submitted_at ASC, d.id ASC
                LIMIT :limit OFFSET :offset
                """, params(filter).addValue("limit", limit).addValue("offset", offset), MAPPER);
    }

    public long count(Filter filter) {
        Long total = jdbc.queryForObject("SELECT count(*) " + FROM, params(filter), Long.class);
        return total == null ? 0 : total;
    }

    private static MapSqlParameterSource params(Filter f) {
        return new MapSqlParameterSource()
                .addValue("status", f.status().name())
                .addValue("matchAll", f.matchAll())
                .addValue("idMatch", f.idMatch())
                .addValue("namePattern", f.namePattern())
                .addValue("digitsOn", f.digitsOn())
                .addValue("digitsPattern", f.digitsPattern())
                .addValue("overdueOnly", f.overdueOnly())
                .addValue("overdueBefore", java.sql.Timestamp.from(f.overdueBefore()))
                .addValue("readyOnly", f.readyOnly())
                .addValue("declarationVersion", f.declarationVersion())
                .addValue("policeRequired", f.policeRequired());
    }

    private static QueueRow mapRow(ResultSet rs) throws SQLException {
        return new QueueRow(rs.getObject("driver_id", UUID.class), rs.getString("name"), rs.getString("phone"),
                rs.getString("city"), DriverVerificationStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("submitted_at").toInstant());
    }
}
