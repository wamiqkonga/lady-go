package com.ladygo.admin.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.domain.DriverDocument;
import com.ladygo.drivers.repository.DriverDocumentRepository;
import com.ladygo.locations.domain.City;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The admin document-view endpoint: role-gated (same class-level @PreAuthorize as every other admin
 *  action here) and reachable only with a real document id - there is no driver-scoped ownership to
 *  bypass, since any admin may view any driver's documents by design. */
@AutoConfigureMockMvc
class AdminVerificationControllerIntegrationTest extends PersistenceTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private DriverDocumentRepository documents;

    private String tokenFor(UUID userId, UserRole role) {
        return jwtService.issueAccessToken(userId, role, Duration.ofMinutes(15));
    }

    private DriverDocument newDocument(Driver driver) {
        DriverDocument document = new DriverDocument();
        document.setDriverId(driver.getId());
        document.setType(DocumentType.LICENSE);
        document.setStorageKey("k-" + UUID.randomUUID());
        return documents.saveAndFlush(document);
    }

    @Test
    void anAdminCanViewADocument() throws Exception {
        City city = newCity();
        Driver driver = newDriver(city);
        DriverDocument document = newDocument(driver);
        UUID adminUserId = newUser(UserRole.ADMIN).getId();

        mockMvc.perform(get("/api/v1/admin/driver-documents/" + document.getId() + "/view")
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    void aDriverCannotCallTheAdminOnlyViewEndpoint() throws Exception {
        City city = newCity();
        Driver driver = newDriver(city);
        DriverDocument document = newDocument(driver);

        mockMvc.perform(get("/api/v1/admin/driver-documents/" + document.getId() + "/view")
                        .header("Authorization", "Bearer " + tokenFor(driver.getUserId(), UserRole.DRIVER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCustomerCannotCallTheAdminOnlyViewEndpoint() throws Exception {
        City city = newCity();
        Driver driver = newDriver(city);
        DriverDocument document = newDocument(driver);
        UUID customerUserId = newCustomer().getUserId();

        mockMvc.perform(get("/api/v1/admin/driver-documents/" + document.getId() + "/view")
                        .header("Authorization", "Bearer " + tokenFor(customerUserId, UserRole.CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewingAnUnknownDocumentIs404() throws Exception {
        UUID adminUserId = newUser(UserRole.ADMIN).getId();

        mockMvc.perform(get("/api/v1/admin/driver-documents/" + UUID.randomUUID() + "/view")
                        .header("Authorization", "Bearer " + tokenFor(adminUserId, UserRole.ADMIN)))
                .andExpect(status().isNotFound());
    }
}
