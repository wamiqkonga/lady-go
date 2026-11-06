package com.ladygo.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ladygo.drivers.domain.DocumentStatus;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.locations.domain.City;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.domain.VehicleType;
import com.ladygo.vehicles.domain.VehicleVerificationStatus;
import com.ladygo.verification.domain.SubjectType;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** docs/product/admin-flows.md section 4: one row per captain waiting for a decision, oldest first. */
@AutoConfigureMockMvc
class AdminDriverVerificationQueueIntegrationTest extends PersistenceTestSupport {

    private static final String QUEUE = "/api/v1/admin/driver-verification/queue";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper json;

    private String token(UserRole role) {
        return "Bearer " + jwtService.issueAccessToken(newUser(role).getId(), role, Duration.ofMinutes(15));
    }

    private JsonNode queue(String query) throws Exception {
        MvcResult result = mockMvc.perform(get(QUEUE + query).header("Authorization", token(UserRole.ADMIN)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("data");
    }

    /** A captain who started registering {@code hoursAgo} hours ago (the first upload is back-dated). */
    private Driver captain(String name, DriverVerificationStatus status, long minutesAgo) {
        City city = newCity();
        Driver driver = newDriver(city);
        User user = users.findById(driver.getUserId()).orElseThrow();
        user.setName(name);
        users.saveAndFlush(user);
        driver.setVerificationStatus(status);
        drivers.saveAndFlush(driver);
        // Back-date the whole registration so the first upload is exactly this old.
        jdbc.update("UPDATE drivers SET created_at = ? WHERE id = ?", ts(minutesAgo + 5), driver.getId());
        upload(driver, DocumentType.VEHICLE_REGISTRATION, DocumentStatus.PENDING, minutesAgo);
        return driver;
    }

    private static java.sql.Timestamp ts(long minutesAgo) {
        return java.sql.Timestamp.from(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES));
    }

    private void upload(Driver driver, DocumentType type, DocumentStatus docStatus, long minutesAgo) {
        jdbc.update("INSERT INTO driver_documents (driver_id, type, storage_key, status, created_at) VALUES (?, ?, ?, ?, ?)",
                driver.getId(), type.name(), "k-" + UUID.randomUUID(), docStatus.name(), ts(minutesAgo));
    }

    private Vehicle vehicle(Driver driver, VehicleVerificationStatus vStatus, String number) {
        Vehicle v = new Vehicle();
        v.setDriverId(driver.getId());
        v.setRegistrationNumber(number);
        v.setVehicleType(VehicleType.BIKE);
        v.setVerificationStatus(vStatus);
        return vehicles.saveAndFlush(v);
    }

    private static List<String> names(JsonNode page) {
        return page.get("content").findValuesAsText("driverName");
    }

    // ---- roles ------------------------------------------------------------------------------

    @Test
    void adminAndSuperAdminMayReadTheQueue() throws Exception {
        for (UserRole role : List.of(UserRole.ADMIN, UserRole.SUPER_ADMIN)) {
            mockMvc.perform(get(QUEUE).header("Authorization", token(role))).andExpect(status().isOk());
        }
    }

    @Test
    void supportDriverAndCustomerAreForbiddenAndAnonymousIsUnauthorised() throws Exception {
        for (UserRole role : List.of(UserRole.SUPPORT, UserRole.DRIVER, UserRole.CUSTOMER)) {
            mockMvc.perform(get(QUEUE).header("Authorization", token(role))).andExpect(status().isForbidden());
        }
        mockMvc.perform(get(QUEUE)).andExpect(status().isUnauthorized());
    }

    // ---- ordering and membership -----------------------------------------------------------

    @Test
    void oldestSubmissionIsFirstAndOnlyCaptainsAwaitingADecisionAppear() throws Exception {
        String tag = "Ord" + UUID.randomUUID().toString().substring(0, 6);
        captain(tag + " newest", DriverVerificationStatus.PENDING_REVIEW, 30);
        captain(tag + " oldest", DriverVerificationStatus.PENDING_REVIEW, 3000);
        captain(tag + " middle", DriverVerificationStatus.PENDING_REVIEW, 600);
        captain(tag + " approved", DriverVerificationStatus.APPROVED, 9000);
        captain(tag + " fresh", DriverVerificationStatus.NOT_SUBMITTED, 9000);

        JsonNode page = queue("?q=" + tag);

        assertThat(names(page)).containsExactly(tag + " oldest", tag + " middle", tag + " newest");
    }

    @Test
    void submittedAtIsTheFirstUploadAcrossDocumentsVehicleAndDeclaration() throws Exception {
        String tag = "First" + UUID.randomUUID().toString().substring(0, 6);
        Driver driver = captain(tag, DriverVerificationStatus.PENDING_REVIEW, 100);
        upload(driver, DocumentType.SELFIE, DocumentStatus.PENDING, 400); // earlier than the RC photo
        JsonNode row = queue("?q=" + tag).get("content").get(0);

        Instant submitted = Instant.parse(row.get("submittedAt").asText());
        assertThat(Duration.between(submitted, Instant.now()).toMinutes()).isBetween(399L, 401L);
        assertThat(row.get("waitingSeconds").asLong()).isBetween(399L * 60, 401L * 60);
    }

    // ---- 24 hour boundary --------------------------------------------------------------------

    @Test
    void overdueMeansWaitingMoreThanTwentyFourHours() throws Exception {
        String tag = "Day" + UUID.randomUUID().toString().substring(0, 6);
        captain(tag + " under", DriverVerificationStatus.PENDING_REVIEW, 24 * 60 - 2);
        captain(tag + " over", DriverVerificationStatus.PENDING_REVIEW, 24 * 60 + 2);

        JsonNode rows = queue("?q=" + tag).get("content");

        assertThat(rows.get(0).get("driverName").asText()).endsWith("over");
        assertThat(rows.get(0).get("overdue").asBoolean()).isTrue();
        assertThat(rows.get(1).get("overdue").asBoolean()).isFalse();
        assertThat(names(queue("?q=" + tag + "&overdueOnly=true"))).hasSize(1).allMatch(n -> n.endsWith("over"));
    }

    // ---- counts ------------------------------------------------------------------------------

    @Test
    void countsApprovedPendingRejectedAndMissingRequiredItems() throws Exception {
        String tag = "Cnt" + UUID.randomUUID().toString().substring(0, 6);
        // vehicle + RC approved, selfie rejected, declaration missing, a licence provided.
        Driver d = captain(tag, DriverVerificationStatus.PENDING_REVIEW, 60);
        jdbc.update("UPDATE driver_documents SET status = 'APPROVED' WHERE driver_id = ?", d.getId());
        vehicle(d, VehicleVerificationStatus.APPROVED, "JK01" + tag.toUpperCase());
        upload(d, DocumentType.SELFIE, DocumentStatus.REJECTED, 50);
        upload(d, DocumentType.LICENSE, DocumentStatus.PENDING, 40);

        JsonNode row = queue("?q=" + tag).get("content").get(0);

        assertThat(row.get("requiredItems").get("approved").asInt()).isEqualTo(1);
        assertThat(row.get("requiredItems").get("pending").asInt()).isZero();
        assertThat(row.get("requiredItems").get("rejected").asInt()).isEqualTo(1);
        assertThat(row.get("requiredItems").get("missing").asInt()).isEqualTo(1);
        assertThat(row.get("optionalDocumentsProvided").asBoolean()).isTrue();
        assertThat(row.get("readyForReview").asBoolean()).isFalse();
        assertThat(row.get("vehicleNumber").asText()).startsWith("JK01");
    }

    @Test
    void aCaptainWithEverythingPendingIsReadyForReviewAndAResubmissionSupersedesTheRejectedUpload() throws Exception {
        String tag = "Rdy" + UUID.randomUUID().toString().substring(0, 6);
        Driver d = captain(tag, DriverVerificationStatus.PENDING_REVIEW, 60);
        vehicle(d, VehicleVerificationStatus.PENDING, "JK02" + tag.toUpperCase());
        upload(d, DocumentType.SELFIE, DocumentStatus.REJECTED, 50);
        upload(d, DocumentType.SELFIE, DocumentStatus.PENDING, 10); // the newer upload is what counts
        acceptCurrentDeclaration(SubjectType.DRIVER, d.getId());

        JsonNode row = queue("?q=" + tag).get("content").get(0);

        // vehicle (RC photo + vehicle) and selfie await review; the accepted declaration needs no review.
        assertThat(row.get("requiredItems").get("pending").asInt()).isEqualTo(2);
        assertThat(row.get("requiredItems").get("approved").asInt()).isEqualTo(1);
        assertThat(row.get("requiredItems").get("rejected").asInt()).isZero();
        assertThat(row.get("requiredItems").get("missing").asInt()).isZero();
        assertThat(row.get("readyForReview").asBoolean()).isTrue();
        assertThat(row.get("optionalDocumentsProvided").asBoolean()).isFalse();
    }

    // ---- filters -----------------------------------------------------------------------------

    @Test
    void searchesByNamePhoneDigitsAndId() throws Exception {
        String tag = "Srch" + UUID.randomUUID().toString().substring(0, 6);
        Driver target = captain(tag + " Zaina", DriverVerificationStatus.PENDING_REVIEW, 20);
        captain(tag + " Other", DriverVerificationStatus.PENDING_REVIEW, 20);
        String phone = users.findById(target.getUserId()).orElseThrow().getPhone();
        String digits = phone.replaceAll("\\D", "");

        assertThat(names(queue("?q=zaina"))).contains(tag + " Zaina").doesNotContain(tag + " Other");
        assertThat(names(queue("?q=" + digits.substring(digits.length() - 8)))).containsExactly(tag + " Zaina");
        assertThat(names(queue("?q=" + digits))).containsExactly(tag + " Zaina");
        assertThat(names(queue("?q=" + target.getId()))).containsExactly(tag + " Zaina");
        assertThat(queue("?q=" + tag + "nomatch").get("content")).isEmpty();
    }

    @Test
    void statusFilterDefaultsToPendingReviewAndCanListRejected() throws Exception {
        String tag = "Stat" + UUID.randomUUID().toString().substring(0, 6);
        captain(tag + " pending", DriverVerificationStatus.PENDING_REVIEW, 20);
        captain(tag + " rejected", DriverVerificationStatus.REJECTED, 20);

        assertThat(names(queue("?q=" + tag))).containsExactly(tag + " pending");
        assertThat(names(queue("?q=" + tag + "&status=REJECTED"))).containsExactly(tag + " rejected");
        mockMvc.perform(get(QUEUE + "?status=NOT_A_STATUS").header("Authorization", token(UserRole.ADMIN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readyOnlyHidesCaptainsWhoStillHaveMissingItems() throws Exception {
        String tag = "Only" + UUID.randomUUID().toString().substring(0, 6);
        captain(tag + " incomplete", DriverVerificationStatus.PENDING_REVIEW, 60);
        Driver ready = captain(tag + " ready", DriverVerificationStatus.PENDING_REVIEW, 30);
        vehicle(ready, VehicleVerificationStatus.PENDING, "JK03" + tag.toUpperCase());
        upload(ready, DocumentType.SELFIE, DocumentStatus.PENDING, 20);
        acceptCurrentDeclaration(SubjectType.DRIVER, ready.getId());

        assertThat(names(queue("?q=" + tag + "&readyOnly=true"))).containsExactly(tag + " ready");
    }

    // ---- paging ------------------------------------------------------------------------------

    @Test
    void pagesAreBoundedEvenWhenAHugeSizeIsRequested() throws Exception {
        String tag = "Pg" + UUID.randomUUID().toString().substring(0, 6);
        for (int i = 0; i < 3; i++) {
            captain(tag + " " + i, DriverVerificationStatus.PENDING_REVIEW, 100 - i);
        }

        JsonNode capped = queue("?q=" + tag + "&size=100000");
        assertThat(capped.get("size").asInt()).isEqualTo(100);

        JsonNode second = queue("?q=" + tag + "&size=2&page=1");
        assertThat(second.get("totalElements").asLong()).isEqualTo(3);
        assertThat(second.get("totalPages").asInt()).isEqualTo(2);
        assertThat(names(second)).containsExactly(tag + " 2");

        assertThat(queue("?q=" + tag + "&size=0").get("size").asInt()).isPositive();
        mockMvc.perform(get(QUEUE + "?page=-1").header("Authorization", token(UserRole.ADMIN)))
                .andExpect(status().is2xxSuccessful());
    }

    // ---- privacy -----------------------------------------------------------------------------

    @Test
    void noUnmaskedPhoneNumberLeavesTheServer() throws Exception {
        String tag = "Mask" + UUID.randomUUID().toString().substring(0, 6);
        Driver d = captain(tag, DriverVerificationStatus.PENDING_REVIEW, 20);
        String phone = users.findById(d.getUserId()).orElseThrow().getPhone();

        MvcResult result = mockMvc.perform(get(QUEUE + "?q=" + tag).header("Authorization", token(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].phoneMasked").value(
                        "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 4)))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(phone).doesNotContain(phone.substring(1, phone.length() - 4));
    }
}
