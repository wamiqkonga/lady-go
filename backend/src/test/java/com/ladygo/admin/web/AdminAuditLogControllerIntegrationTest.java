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
}
