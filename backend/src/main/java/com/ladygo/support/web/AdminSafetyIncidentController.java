package com.ladygo.support.web;

import java.util.EnumSet;
import java.util.List;
import com.ladygo.common.api.ApiResponse;
import com.ladygo.support.domain.IncidentStatus;
import com.ladygo.support.repository.SafetyIncidentRepository;
import com.ladygo.support.web.dto.SafetyIncidentResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The safety incident queue (docs/api/safety.md section 2): every incident that is not resolved yet. */
@RestController
@RequestMapping("/api/v1/admin/safety-incidents")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'SUPPORT')")
public class AdminSafetyIncidentController {

    private final SafetyIncidentRepository incidents;

    public AdminSafetyIncidentController(SafetyIncidentRepository incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ApiResponse<List<SafetyIncidentResponse>> openIncidents() {
        EnumSet<IncidentStatus> open = EnumSet.of(
                IncidentStatus.OPEN, IncidentStatus.ACKNOWLEDGED, IncidentStatus.ESCALATED);
        return ApiResponse.ok(incidents.findByStatusInOrderBySeverityDescCreatedAtAsc(open).stream()
                .map(i -> new SafetyIncidentResponse(
                        i.getId(), i.getReporterId(), i.getBookingId(), i.getRideId(),
                        i.getType().name(), i.getSeverity().name(), i.getStatus().name(),
                        i.getLocation() == null ? null : i.getLocation().getY(),
                        i.getLocation() == null ? null : i.getLocation().getX(),
                        i.getNotes(), i.getCreatedAt()))
                .toList());
    }
}
