package com.ladygo.admin.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.admin.web.dto.SafetyEventResponse;
import com.ladygo.admin.web.dto.SafetyIncidentDetailResponse;
import com.ladygo.admin.web.dto.SafetyIncidentSummaryResponse;
import com.ladygo.admin.web.dto.SafetyLocationResponse;
import com.ladygo.admin.web.dto.SafetyOpenCountResponse;
import com.ladygo.admin.web.dto.SafetyOutcome;
import com.ladygo.admin.web.dto.SafetyReporterResponse;
import com.ladygo.common.api.PageResponse;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.rides.repository.RideRepository;
import com.ladygo.support.domain.IncidentSeverity;
import com.ladygo.support.domain.IncidentStatus;
import com.ladygo.support.domain.SafetyIncident;
import com.ladygo.support.domain.SafetyIncidentEvent;
import com.ladygo.support.repository.SafetyIncidentEventRepository;
import com.ladygo.support.repository.SafetyIncidentRepository;
import com.ladygo.users.domain.User;
import com.ladygo.users.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The safety queue (docs/development/plans/2026-10-31-admin-portal-completion.md step 5). Every action appends to the
 * incident's timeline (append-only table) and writes an audit entry. Acknowledging and resolving are atomic
 * conditional updates, so two responders cannot both win. Resolving is deliberately not behind step-up MFA: in an
 * emergency speed matters more than re-proving identity, and the actor is audited either way.
 */
@Service
public class AdminSafetyService {

    private static final Set<IncidentStatus> UNRESOLVED =
            Set.of(IncidentStatus.OPEN, IncidentStatus.ACKNOWLEDGED, IncidentStatus.ESCALATED);
    private static final int MAX_PAGE_SIZE = 100;

    private final SafetyIncidentRepository incidents;
    private final SafetyIncidentEventRepository events;
    private final UserRepository users;
    private final RideRepository rides;
    private final AuditLogRepository auditLogs;

    public AdminSafetyService(SafetyIncidentRepository incidents, SafetyIncidentEventRepository events,
                              UserRepository users, RideRepository rides, AuditLogRepository auditLogs) {
        this.incidents = incidents;
        this.events = events;
        this.users = users;
        this.rides = rides;
        this.auditLogs = auditLogs;
    }

    @Transactional(readOnly = true)
    public PageResponse<SafetyIncidentSummaryResponse> list(IncidentStatus status, Pageable requested) {
        Pageable pageable = PageRequest.of(requested.getPageNumber(), Math.min(requested.getPageSize(), MAX_PAGE_SIZE));
        var page = status == IncidentStatus.RESOLVED
                ? incidents.findByStatusOrderByCreatedAtDesc(IncidentStatus.RESOLVED, pageable)
                : incidents.queue(status == null ? UNRESOLVED : Set.of(status), pageable);
        Map<UUID, User> reporters = usersById(page.getContent().stream().map(SafetyIncident::getReporterId).toList());
        return PageResponse.of(page.map(i -> toSummary(i, reporters.get(i.getReporterId()))));
    }

    @Transactional(readOnly = true)
    public SafetyOpenCountResponse openCount() {
        return new SafetyOpenCountResponse(
                incidents.countByStatusIn(UNRESOLVED),
                incidents.countByStatus(IncidentStatus.OPEN),
                incidents.countBySeverityAndStatusIn(IncidentSeverity.CRITICAL, UNRESOLVED));
    }

    @Transactional(readOnly = true)
    public SafetyIncidentDetailResponse detail(UUID id) {
        return toDetail(load(id));
    }

    @Transactional
    public SafetyIncidentDetailResponse acknowledge(UUID id, UUID actorId, String actorRole, String ip) {
        load(id);
        if (incidents.acknowledgeIfOpen(id, actorId, Instant.now()) == 0) {
            throw new ValidationException("This incident was already acknowledged or resolved.");
        }
        appendEvent(id, actorId, "ACKNOWLEDGED", null, null);
        audit(actorId, actorRole, "SAFETY_INCIDENT_ACKNOWLEDGED", id, ip, Map.of());
        return toDetail(load(id));
    }

    @Transactional
    public SafetyIncidentDetailResponse addNote(UUID id, String note, UUID actorId, String actorRole, String ip) {
        SafetyIncident incident = load(id);
        if (incident.getStatus() == IncidentStatus.RESOLVED) {
            throw new ValidationException("This incident is resolved; notes can no longer be added.");
        }
        appendEvent(id, actorId, "NOTE", note.trim(), null);
        audit(actorId, actorRole, "SAFETY_INCIDENT_NOTE_ADDED", id, ip, Map.of("note", note.trim()));
        return toDetail(incident);
    }

    @Transactional
    public SafetyIncidentDetailResponse resolve(UUID id, SafetyOutcome outcome, String note, UUID actorId,
                                                String actorRole, String ip) {
        load(id);
        if (incidents.resolveIfUnresolved(id, actorId, Instant.now()) == 0) {
            throw new ValidationException("This incident is already resolved.");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        appendEvent(id, actorId, "RESOLVED", cleanNote, Map.of("outcome", outcome.name()));
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("outcome", outcome.name());
        if (cleanNote != null) {
            metadata.put("note", cleanNote);
        }
        audit(actorId, actorRole, "SAFETY_INCIDENT_RESOLVED", id, ip, metadata);
        return toDetail(load(id));
    }

    private SafetyIncident load(UUID id) {
        return incidents.findById(id).orElseThrow(ResourceNotFoundException::new);
    }

    private void appendEvent(UUID incidentId, UUID actorId, String type, String notes, Map<String, Object> metadata) {
        SafetyIncidentEvent event = new SafetyIncidentEvent();
        event.setIncidentId(incidentId);
        event.setActorId(actorId);
        event.setEventType(type);
        event.setNotes(notes);
        event.setMetadata(metadata);
        events.save(event);
    }

    private void audit(UUID actorId, String actorRole, String action, UUID incidentId, String ip, Map<String, Object> metadata) {
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setActorRole(actorRole);
        entry.setAction(action);
        entry.setTargetType("safety_incidents");
        entry.setTargetId(incidentId.toString());
        entry.setIp(ip);
        entry.setMetadata(metadata.isEmpty() ? null : metadata);
        auditLogs.save(entry);
    }

    private Map<UUID, User> usersById(List<UUID> ids) {
        Map<UUID, User> byId = new HashMap<>();
        users.findAllById(new LinkedHashSet<>(ids)).forEach(u -> byId.put(u.getId(), u));
        return byId;
    }

    private static SafetyReporterResponse reporterOf(User user) {
        if (user == null) {
            return null;
        }
        return new SafetyReporterResponse(user.getName(), user.getRole().name(), AdminRideOversightService.maskPhone(user.getPhone()));
    }

    private static SafetyIncidentSummaryResponse toSummary(SafetyIncident i, User reporter) {
        return new SafetyIncidentSummaryResponse(i.getId(), i.getRideId(), i.getType().name(), i.getSeverity().name(),
                i.getStatus().name(), reporterOf(reporter), i.getLocation() != null, i.getCreatedAt(), i.getAcknowledgedAt());
    }

    private SafetyIncidentDetailResponse toDetail(SafetyIncident i) {
        List<SafetyIncidentEvent> timeline = events.findByIncidentIdOrderByCreatedAtAsc(i.getId());
        List<UUID> userIds = new java.util.ArrayList<>();
        userIds.add(i.getReporterId());
        timeline.stream().map(SafetyIncidentEvent::getActorId).filter(java.util.Objects::nonNull).forEach(userIds::add);
        Map<UUID, User> byId = usersById(userIds);
        UUID bookingId = rides.findById(i.getRideId()).map(r -> r.getBookingId()).orElse(null);
        SafetyLocationResponse location = i.getLocation() == null
                ? null : new SafetyLocationResponse(i.getLocation().getY(), i.getLocation().getX());
        List<SafetyEventResponse> eventResponses = timeline.stream().map(e -> {
            User actor = e.getActorId() == null ? null : byId.get(e.getActorId());
            Object outcome = e.getMetadata() == null ? null : e.getMetadata().get("outcome");
            return new SafetyEventResponse(e.getId(), e.getEventType(), actor == null ? null : actor.getName(),
                    actor == null ? null : actor.getRole().name(), e.getNotes(), outcome == null ? null : outcome.toString(),
                    e.getCreatedAt());
        }).toList();
        return new SafetyIncidentDetailResponse(i.getId(), i.getRideId(), bookingId, i.getType().name(), i.getSeverity().name(),
                i.getStatus().name(), reporterOf(byId.get(i.getReporterId())), location, i.getNotes(), i.isLegalHold(),
                i.getCreatedAt(), i.getAcknowledgedAt(), i.getResolvedAt(), eventResponses);
    }
}
