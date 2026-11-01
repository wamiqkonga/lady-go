package com.ladygo.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.bookings.domain.Booking;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.locations.domain.City;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.support.domain.IncidentSeverity;
import com.ladygo.support.domain.IncidentStatus;
import com.ladygo.support.domain.IncidentType;
import com.ladygo.support.domain.SafetyIncident;
import com.ladygo.support.domain.SafetyIncidentEvent;
import com.ladygo.support.repository.SafetyIncidentEventRepository;
import com.ladygo.support.repository.SafetyIncidentRepository;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** docs/development/plans/2026-10-31-admin-portal-completion.md steps 5: the safety queue. SUPPORT and above can read
 *  and act; a rider or captain token cannot reach it at all. Resolving is deliberately not behind step-up (speed matters
 *  in an emergency), but every action is audited and appended to the incident's timeline. */
@AutoConfigureMockMvc
class AdminSafetyControllerIntegrationTest extends PersistenceTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger(100000);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private SafetyIncidentRepository incidents;
    @Autowired private SafetyIncidentEventRepository events;
    @Autowired private AuditLogRepository auditLogs;

    private String tokenFor(User user) {
        return jwtService.issueAccessToken(user.getId(), user.getRole(), Duration.ofMinutes(10));
    }

    private User staff(UserRole role) {
        return newUser(role);
    }

    private SafetyIncident incident(IncidentType type, IncidentSeverity severity, IncidentStatus status) {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        Booking booking = newBooking(customer, city, BookingStatus.RIDE_STARTED, driver.getId());
        var ride = newRide(booking, driver);
        User reporter = users.findById(customer.getUserId()).orElseThrow();
        reporter.setName("Asma Rider");
        reporter.setPhone("+91" + SEQ.incrementAndGet() + "3210");
        users.saveAndFlush(reporter);

        SafetyIncident incident = new SafetyIncident();
        incident.setRideId(ride.getId());
        incident.setReporterId(reporter.getId());
        incident.setType(type);
        incident.setSeverity(severity);
        incident.setStatus(status);
        incident.setLocation(point(74.8, 34.08));
        incident.setNotes("Driver took a wrong turn");
        SafetyIncident saved = incidents.saveAndFlush(incident);

        SafetyIncidentEvent created = new SafetyIncidentEvent();
        created.setIncidentId(saved.getId());
        created.setActorId(reporter.getId());
        created.setEventType("CREATED");
        events.saveAndFlush(created);
        return saved;
    }

    private ResultActions getAs(User user, String url) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + tokenFor(user)));
    }

    private ResultActions postAs(User user, String url, String body) throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + tokenFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // --- access ------------------------------------------------------------------------------------------------

    @Test
    void ridersAndCaptainsAndAnonymousCallersCannotReachTheQueue() throws Exception {
        User customer = newUser(UserRole.CUSTOMER);
        User driver = newUser(UserRole.DRIVER);

        getAs(customer, "/api/v1/admin/safety/incidents").andExpect(status().isForbidden());
        getAs(driver, "/api/v1/admin/safety/incidents").andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/safety/incidents")).andExpect(status().isUnauthorized());
    }

    @Test
    void supportAdminAndSuperAdminCanReadTheQueue() throws Exception {
        for (UserRole role : new UserRole[] {UserRole.SUPPORT, UserRole.ADMIN, UserRole.SUPER_ADMIN}) {
            getAs(staff(role), "/api/v1/admin/safety/incidents")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isArray());
        }
    }

    // --- the queue ---------------------------------------------------------------------------------------------

    @Test
    void theQueueShowsOnlyUnresolvedIncidentsMostSevereFirstThenOldestFirst() throws Exception {
        SafetyIncident low = incident(IncidentType.REPORT_DRIVER, IncidentSeverity.LOW, IncidentStatus.OPEN);
        SafetyIncident critical = incident(IncidentType.SOS, IncidentSeverity.CRITICAL, IncidentStatus.OPEN);
        SafetyIncident highNewer = incident(IncidentType.REPORT_CUSTOMER, IncidentSeverity.HIGH, IncidentStatus.OPEN);
        SafetyIncident highOlder = incident(IncidentType.REPORT_CUSTOMER, IncidentSeverity.HIGH, IncidentStatus.ACKNOWLEDGED);
        SafetyIncident medium = incident(IncidentType.OTHER, IncidentSeverity.MEDIUM, IncidentStatus.OPEN);
        incident(IncidentType.SOS, IncidentSeverity.CRITICAL, IncidentStatus.RESOLVED);
        jdbc.update("UPDATE safety_incidents SET created_at = now() - interval '2 hours' WHERE id = ?", highOlder.getId());
        jdbc.update("UPDATE safety_incidents SET created_at = now() - interval '1 hour' WHERE id = ?", highNewer.getId());
        flushAndClear();

        getAs(staff(UserRole.SUPPORT), "/api/v1/admin/safety/incidents")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(5))
                .andExpect(jsonPath("$.data.content[0].id").value(critical.getId().toString()))
                .andExpect(jsonPath("$.data.content[1].id").value(highOlder.getId().toString()))
                .andExpect(jsonPath("$.data.content[2].id").value(highNewer.getId().toString()))
                .andExpect(jsonPath("$.data.content[3].id").value(medium.getId().toString()))
                .andExpect(jsonPath("$.data.content[4].id").value(low.getId().toString()));
    }

    @Test
    void theStatusFilterCanShowResolvedIncidents() throws Exception {
        incident(IncidentType.SOS, IncidentSeverity.CRITICAL, IncidentStatus.OPEN);
        SafetyIncident done = incident(IncidentType.OTHER, IncidentSeverity.LOW, IncidentStatus.RESOLVED);

        getAs(staff(UserRole.SUPPORT), "/api/v1/admin/safety/incidents?status=RESOLVED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(done.getId().toString()));
    }

    @Test
    void theOpenCountCoversEveryUnresolvedStatusAndFlagsCriticalOnes() throws Exception {
        incident(IncidentType.SOS, IncidentSeverity.CRITICAL, IncidentStatus.OPEN);
        incident(IncidentType.SOS, IncidentSeverity.CRITICAL, IncidentStatus.ACKNOWLEDGED);
        incident(IncidentType.OTHER, IncidentSeverity.LOW, IncidentStatus.ESCALATED);
        incident(IncidentType.OTHER, IncidentSeverity.LOW, IncidentStatus.RESOLVED);

        getAs(staff(UserRole.SUPPORT), "/api/v1/admin/safety/incidents/open-count")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unresolved").value(3))
                .andExpect(jsonPath("$.data.needsAcknowledging").value(1))
                .andExpect(jsonPath("$.data.critical").value(2));
    }

    // --- detail ------------------------------------------------------------------------------------------------

    @Test
    void detailShowsTheTimelineTheLocationAndOnlyAMaskedPhone() throws Exception {
        SafetyIncident incident = incident(IncidentType.SOS, IncidentSeverity.CRITICAL, IncidentStatus.OPEN);
        String fullPhone = users.findById(incident.getReporterId()).orElseThrow().getPhone();

        getAs(staff(UserRole.SUPPORT), "/api/v1/admin/safety/incidents/" + incident.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("SOS"))
                .andExpect(jsonPath("$.data.reporter.name").value("Asma Rider"))
                .andExpect(jsonPath("$.data.reporter.phoneMasked").value("*".repeat(fullPhone.length() - 4) + "3210"))
                .andExpect(jsonPath("$.data.location.lat").value(34.08))
                .andExpect(jsonPath("$.data.location.lng").value(74.8))
                .andExpect(jsonPath("$.data.timeline[0].eventType").value("CREATED"))
                .andExpect(jsonPath("$.data.bookingId").exists())
                .andExpect(content().string(not(containsString(fullPhone))));
    }

    @Test
    void detailOfAnUnknownIncidentIsNotFound() throws Exception {
        getAs(staff(UserRole.SUPPORT), "/api/v1/admin/safety/incidents/" + UUID.randomUUID())
                .andExpect(status().isNotFound());
    }

    // --- acknowledge -------------------------------------------------------------------------------------------

    @Test
    void acknowledgingMarksItAddsATimelineEventAndIsAudited() throws Exception {
        SafetyIncident incident = incident(IncidentType.SOS, IncidentSeverity.CRITICAL, IncidentStatus.OPEN);
        User agent = staff(UserRole.SUPPORT);

        postAs(agent, "/api/v1/admin/safety/incidents/" + incident.getId() + "/acknowledge", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.acknowledgedAt").exists());

        flushAndClear();
        SafetyIncident reloaded = incidents.findById(incident.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(reloaded.getAcknowledgedBy()).isEqualTo(agent.getId());
        assertThat(events.findByIncidentIdOrderByCreatedAtAsc(incident.getId()))
                .extracting(SafetyIncidentEvent::getEventType).containsExactly("CREATED", "ACKNOWLEDGED");
        assertThat(auditLogs.findAll()).anySatisfy(log -> {
            assertThat(log.getAction()).isEqualTo("SAFETY_INCIDENT_ACKNOWLEDGED");
            assertThat(log.getTargetId()).isEqualTo(incident.getId().toString());
            assertThat(log.getActorId()).isEqualTo(agent.getId());
        });
    }

    @Test
    void anIncidentCanOnlyBeAcknowledgedOnce() throws Exception {
        SafetyIncident incident = incident(IncidentType.SOS, IncidentSeverity.CRITICAL, IncidentStatus.OPEN);
        User agent = staff(UserRole.SUPPORT);
        String url = "/api/v1/admin/safety/incidents/" + incident.getId() + "/acknowledge";

        postAs(agent, url, "{}").andExpect(status().isOk());
        postAs(agent, url, "{}").andExpect(status().isBadRequest());
    }

    @Test
    void aResolvedIncidentCannotBeAcknowledged() throws Exception {
        SafetyIncident incident = incident(IncidentType.SOS, IncidentSeverity.CRITICAL, IncidentStatus.RESOLVED);

        postAs(staff(UserRole.SUPPORT), "/api/v1/admin/safety/incidents/" + incident.getId() + "/acknowledge", "{}")
                .andExpect(status().isBadRequest());
    }

    // --- notes -------------------------------------------------------------------------------------------------

    @Test
    void aNoteIsAppendedToTheTimelineAndAudited() throws Exception {
        SafetyIncident incident = incident(IncidentType.REPORT_DRIVER, IncidentSeverity.HIGH, IncidentStatus.ACKNOWLEDGED);
        User agent = staff(UserRole.ADMIN);

        postAs(agent, "/api/v1/admin/safety/incidents/" + incident.getId() + "/notes",
                "{\"note\":\"Called the rider, she is safe and home.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timeline[1].eventType").value("NOTE"))
                .andExpect(jsonPath("$.data.timeline[1].notes").value("Called the rider, she is safe and home."));

        assertThat(auditLogs.findAll()).anySatisfy(log -> assertThat(log.getAction()).isEqualTo("SAFETY_INCIDENT_NOTE_ADDED"));
    }

    @Test
    void aNoteMustHaveTextAndIsLengthLimited() throws Exception {
        SafetyIncident incident = incident(IncidentType.REPORT_DRIVER, IncidentSeverity.HIGH, IncidentStatus.OPEN);
        User agent = staff(UserRole.SUPPORT);
        String url = "/api/v1/admin/safety/incidents/" + incident.getId() + "/notes";

        postAs(agent, url, "{\"note\":\"   \"}").andExpect(status().isBadRequest());
        postAs(agent, url, "{}").andExpect(status().isBadRequest());
        postAs(agent, url, "{\"note\":\"" + "x".repeat(2001) + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    void noNoteCanBeAddedOnceAnIncidentIsResolved() throws Exception {
        SafetyIncident incident = incident(IncidentType.OTHER, IncidentSeverity.LOW, IncidentStatus.RESOLVED);

        postAs(staff(UserRole.SUPPORT), "/api/v1/admin/safety/incidents/" + incident.getId() + "/notes", "{\"note\":\"late\"}")
                .andExpect(status().isBadRequest());
    }

    // --- resolve -----------------------------------------------------------------------------------------------

    @Test
    void resolvingNeedsAnOutcome() throws Exception {
        SafetyIncident incident = incident(IncidentType.SOS, IncidentSeverity.CRITICAL, IncidentStatus.ACKNOWLEDGED);
        User agent = staff(UserRole.SUPPORT);
        String url = "/api/v1/admin/safety/incidents/" + incident.getId() + "/resolve";

        postAs(agent, url, "{}").andExpect(status().isBadRequest());
        postAs(agent, url, "{\"outcome\":\"MADE_UP\"}").andExpect(status().isBadRequest());
    }

    @Test
    void resolvingRecordsTheOutcomeAndWhoDidItAndIsAudited() throws Exception {
        SafetyIncident incident = incident(IncidentType.SOS, IncidentSeverity.CRITICAL, IncidentStatus.ACKNOWLEDGED);
        User agent = staff(UserRole.SUPPORT);

        postAs(agent, "/api/v1/admin/safety/incidents/" + incident.getId() + "/resolve",
                "{\"outcome\":\"POLICE_INFORMED\",\"note\":\"Police called, rider is with them.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolvedAt").exists());

        flushAndClear();
        SafetyIncident reloaded = incidents.findById(incident.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(reloaded.getResolvedBy()).isEqualTo(agent.getId());
        SafetyIncidentEvent last = events.findByIncidentIdOrderByCreatedAtAsc(incident.getId()).getLast();
        assertThat(last.getEventType()).isEqualTo("RESOLVED");
        assertThat(last.getMetadata()).containsEntry("outcome", "POLICE_INFORMED");
        assertThat(auditLogs.findAll()).anySatisfy(log -> {
            assertThat(log.getAction()).isEqualTo("SAFETY_INCIDENT_RESOLVED");
            assertThat(log.getTargetId()).isEqualTo(incident.getId().toString());
        });
    }

    @Test
    void anUnacknowledgedIncidentCanBeResolvedDirectlyAndNeedsNoStepUp() throws Exception {
        SafetyIncident incident = incident(IncidentType.SOS, IncidentSeverity.CRITICAL, IncidentStatus.OPEN);
        // A SUPER_ADMIN token with no step-up claim: resolving must still work (decision: speed in an emergency).
        User boss = staff(UserRole.SUPER_ADMIN);

        postAs(boss, "/api/v1/admin/safety/incidents/" + incident.getId() + "/resolve", "{\"outcome\":\"FALSE_ALARM\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));
    }

    @Test
    void anIncidentCanOnlyBeResolvedOnce() throws Exception {
        SafetyIncident incident = incident(IncidentType.SOS, IncidentSeverity.CRITICAL, IncidentStatus.OPEN);
        User agent = staff(UserRole.SUPPORT);
        String url = "/api/v1/admin/safety/incidents/" + incident.getId() + "/resolve";

        postAs(agent, url, "{\"outcome\":\"FALSE_ALARM\"}").andExpect(status().isOk());
        postAs(agent, url, "{\"outcome\":\"FALSE_ALARM\"}").andExpect(status().isBadRequest());
    }

    @Test
    void writesAreRefusedForNonStaff() throws Exception {
        SafetyIncident incident = incident(IncidentType.SOS, IncidentSeverity.CRITICAL, IncidentStatus.OPEN);
        User customer = newUser(UserRole.CUSTOMER);

        postAs(customer, "/api/v1/admin/safety/incidents/" + incident.getId() + "/acknowledge", "{}")
                .andExpect(status().isForbidden());
        postAs(customer, "/api/v1/admin/safety/incidents/" + incident.getId() + "/resolve", "{\"outcome\":\"FALSE_ALARM\"}")
                .andExpect(status().isForbidden());
    }
}
