package com.ladygo.admin.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AdminAuditLogControllerIntegrationTest extends PersistenceTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private AuditLogRepository auditLogs;
    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private String tokenFor(UUID userId, UserRole role) {
        return jwtService.issueAccessToken(userId, role, Duration.ofMinutes(15));
    }

    private AuditLog newEntry(UUID actorId, String action) {
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setActorRole("ADMIN");
        entry.setAction(action);
        entry.setTargetType("driver_documents");
        entry.setTargetId(UUID.randomUUID().toString());
        entry.setIp("127.0.0.1");
        return auditLogs.saveAndFlush(entry);
    }

    @Test
    void anAdminCanListAuditLogEntries() throws Exception {
        UUID adminUserId = newUser(UserRole.ADMIN).getId();
        newEntry(adminUserId, "DRIVER_DOCUMENT_VIEWED");

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].action").value("DRIVER_DOCUMENT_VIEWED"));
    }

    @Test
    void filtersByAction() throws Exception {
        UUID adminUserId = newUser(UserRole.ADMIN).getId();
        newEntry(adminUserId, "DRIVER_DOCUMENT_VIEWED");
        newEntry(adminUserId, "DRIVER_APPROVED");

        mockMvc.perform(get("/api/v1/admin/audit-logs").param("action", "DRIVER_APPROVED")
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].action").value("DRIVER_APPROVED"));
    }

    @Test
    void aDriverCannotListAuditLogEntries() throws Exception {
        var driver = newDriver(newCity());

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", "Bearer " + tokenFor(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCustomerCannotListAuditLogEntries() throws Exception {
        var customer = newCustomer();

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", "Bearer " + tokenFor(customer.getUserId(), UserRole.CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void showsTheActorsNameOrAMaskedEmailAndFiltersByEmail() throws Exception {
        var viewer = newUser(UserRole.ADMIN);
        var named = newUser(UserRole.SUPPORT);
        named.setName("Asma Support");
        named.setEmail("asma.support@example.com");
        users.saveAndFlush(named);
        var unnamed = newUser(UserRole.SUPPORT);
        unnamed.setName(null);
        unnamed.setEmail("zed@example.com");
        users.saveAndFlush(unnamed);
        newEntry(named.getId(), "DRIVER_APPROVED");
        newEntry(unnamed.getId(), "DRIVER_REJECTED");

        mockMvc.perform(get("/api/v1/admin/audit-logs").param("action", "DRIVER_APPROVED")
                        .header("Authorization", "Bearer " + tokenFor(viewer.getId(), UserRole.ADMIN)))
                .andExpect(jsonPath("$.data.content[0].actorName").value("Asma Support"))
                .andExpect(jsonPath("$.data.content[0].actorEmailMasked").value("a***@example.com"));
        mockMvc.perform(get("/api/v1/admin/audit-logs").param("action", "DRIVER_REJECTED")
                        .header("Authorization", "Bearer " + tokenFor(viewer.getId(), UserRole.ADMIN)))
                .andExpect(jsonPath("$.data.content[0].actorName").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].actorEmailMasked").value("z***@example.com"));

        mockMvc.perform(get("/api/v1/admin/audit-logs").param("actorEmail", "ZED@example.com")
                        .header("Authorization", "Bearer " + tokenFor(viewer.getId(), UserRole.ADMIN)))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].action").value("DRIVER_REJECTED"));
        mockMvc.perform(get("/api/v1/admin/audit-logs").param("actorEmail", "nobody@example.com")
                        .header("Authorization", "Bearer " + tokenFor(viewer.getId(), UserRole.ADMIN)))
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }

    @Test
    void lookingUpActorsIsBatchedNotOneQueryPerRow() throws Exception {
        var viewer = newUser(UserRole.ADMIN);
        for (int i = 0; i < 5; i++) {
            newEntry(newUser(UserRole.SUPPORT).getId(), "DRIVER_APPROVED");
        }
        flushAndClear();
        var stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        mockMvc.perform(get("/api/v1/admin/audit-logs").param("size", "10")
                        .header("Authorization", "Bearer " + tokenFor(viewer.getId(), UserRole.ADMIN)))
                .andExpect(status().isOk());

        // list page + count + ONE batched user lookup, however many distinct actors the page has.
        org.assertj.core.api.Assertions.assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(4);
    }
}
