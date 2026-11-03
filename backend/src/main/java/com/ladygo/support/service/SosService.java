package com.ladygo.support.service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.geo.GeoPoints;
import com.ladygo.config.ConfigurationService;
import com.ladygo.customers.repository.CustomerRepository;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.rides.domain.Ride;
import com.ladygo.rides.repository.RideRepository;
import com.ladygo.security.ratelimit.RateLimiter;
import com.ladygo.support.domain.IncidentSeverity;
import com.ladygo.support.domain.IncidentStatus;
import com.ladygo.support.domain.IncidentType;
import com.ladygo.support.domain.SafetyIncident;
import com.ladygo.support.repository.SafetyIncidentRepository;
import com.ladygo.support.web.dto.SosRequest;
import com.ladygo.support.web.dto.SosResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * docs/api/safety.md sections 1 and 7. One SOS = one CRITICAL, OPEN incident under legal hold, plus its CREATED
 * timeline event. The incident row is the admin queue. Coordinates are client supplied on purpose (section 6).
 * Rate limiting only bounds how many queue rows a reporter can create; it never hides the failure from the caller.
 */
@Service
public class SosService {

    private static final Logger log = LoggerFactory.getLogger(SosService.class);
    static final String AUDIT_ACTION = "SAFETY_SOS_RAISED";

    private final SafetyIncidentRepository incidents;
    private final BookingRepository bookings;
    private final RideRepository rides;
    private final CustomerRepository customers;
    private final DriverRepository drivers;
    private final JdbcTemplate jdbc;
    private final ConfigurationService config;
    private final RateLimiter rateLimiter;
    private final AuditLogRepository auditLogs;

    public SosService(SafetyIncidentRepository incidents, BookingRepository bookings, RideRepository rides,
            CustomerRepository customers, DriverRepository drivers, JdbcTemplate jdbc, ConfigurationService config,
            RateLimiter rateLimiter, AuditLogRepository auditLogs) {
        this.incidents = incidents;
        this.bookings = bookings;
        this.rides = rides;
        this.customers = customers;
        this.drivers = drivers;
        this.jdbc = jdbc;
        this.config = config;
        this.rateLimiter = rateLimiter;
        this.auditLogs = auditLogs;
    }

    @Transactional
    public SosResponse raiseForCustomer(UUID customerUserId, SosRequest request) {
        UUID customerId = customers.findByUserId(customerUserId).orElseThrow(ResourceNotFoundException::new).getId();
        Booking booking = ownBooking(request.bookingId(), b -> customerId.equals(b.getCustomerId()));
        return raise(customerUserId, "CUSTOMER", booking, request);
    }

    @Transactional
    public SosResponse raiseForDriver(UUID driverUserId, SosRequest request) {
        UUID driverId = drivers.findByUserId(driverUserId).orElseThrow(ResourceNotFoundException::new).getId();
        Booking booking = ownBooking(request.bookingId(), b -> driverId.equals(b.getDriverId()));
        return raise(driverUserId, "DRIVER", booking, request);
    }

    private Booking ownBooking(UUID bookingId, java.util.function.Predicate<Booking> isCallers) {
        if (bookingId == null) {
            return null;
        }
        return bookings.findById(bookingId).filter(isCallers).orElseThrow(ResourceNotFoundException::new);
    }

    private SosResponse raise(UUID reporterUserId, String role, Booking booking, SosRequest request) {
        int max = config.getInt("sos.rate.max.attempts", 3);
        Duration window = config.getMinutes("sos.rate.window.minutes", Duration.ofMinutes(15));
        if (!rateLimiter.tryConsume("sos:" + reporterUserId, max, window)) {
            throw new RateLimitExceededException();
        }
        SafetyIncident incident = new SafetyIncident();
        incident.setReporterId(reporterUserId);
        incident.setType(IncidentType.SOS);
        incident.setSeverity(IncidentSeverity.CRITICAL);
        incident.setStatus(IncidentStatus.OPEN);
        incident.setLegalHold(true);
        incident.setLocation(GeoPoints.of(request.latitude(), request.longitude()));
        incident.setNotes(request.notes());
        if (booking != null) {
            incident.setBookingId(booking.getId());
            incident.setRideId(rides.findByBookingId(booking.getId()).map(Ride::getId).orElse(null));
        }
        SafetyIncident saved = incidents.saveAndFlush(incident);
        jdbc.update("INSERT INTO safety_incident_events (incident_id, actor_id, event_type) VALUES (?, ?, 'CREATED')",
                saved.getId(), reporterUserId);
        audit(reporterUserId, role, saved.getId());
        // Ids only: no coordinates or notes in logs.
        log.warn("operation=sos_raised incidentId={} reporterId={} role={}", saved.getId(), reporterUserId, role);
        return new SosResponse(saved.getId(), saved.getCreatedAt());
    }

    private void audit(UUID actorId, String role, UUID incidentId) {
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setActorRole(role);
        entry.setAction(AUDIT_ACTION);
        entry.setTargetType("safety_incidents");
        entry.setTargetId(incidentId.toString());
        entry.setMetadata(Map.of("incidentType", IncidentType.SOS.name()));
        auditLogs.save(entry);
    }
}
