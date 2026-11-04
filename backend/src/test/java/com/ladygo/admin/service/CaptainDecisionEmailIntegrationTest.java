package com.ladygo.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.notifications.email.EmailSender;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.domain.VehicleVerificationStatus;
import com.ladygo.verification.domain.SubjectType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The captain is told by email when a driver, document or vehicle decision is made. Deliberately NOT wrapped in a
 * test transaction: what matters is that the email goes out only after the decision has really committed, and a
 * surrounding transaction would hide exactly that. Rows are removed in {@link #cleanUp}.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CaptainDecisionEmailIntegrationTest extends PersistenceTestSupport {

    private static final String REASON = "Photo is blurry <b>& dark</b>; line two\nplease retake it.";

    @Autowired private AdminVerificationService service;
    @Autowired private PlatformTransactionManager txManager;

    @MockitoBean private EmailSender emailSender;

    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> driverIds = new ArrayList<>();
    private final List<UUID> cityIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : driverIds) {
            jdbc.update("DELETE FROM driver_documents WHERE driver_id = ?", id);
            jdbc.update("DELETE FROM vehicles WHERE driver_id = ?", id);
            jdbc.update("DELETE FROM self_declarations WHERE subject_id = ?", id);
            jdbc.update("DELETE FROM audit_logs WHERE target_id = ?", id.toString());
            jdbc.update("DELETE FROM drivers WHERE id = ?", id);
        }
        for (UUID id : userIds) {
            jdbc.update("DELETE FROM audit_logs WHERE actor_id = ? OR target_id = ?", id, id.toString());
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        for (UUID id : cityIds) {
            jdbc.update("DELETE FROM cities WHERE id = ?", id);
        }
        driverIds.clear();
        userIds.clear();
        cityIds.clear();
    }

    private UUID admin() {
        UUID id = newUser(UserRole.ADMIN).getId();
        userIds.add(id);
        return id;
    }

    private Driver captain(String email) {
        var city = newCity();
        cityIds.add(city.getId());
        Driver driver = newDriver(city);
        driverIds.add(driver.getId());
        userIds.add(driver.getUserId());
        User user = users.findById(driver.getUserId()).orElseThrow();
        user.setName("Asma Test");
        user.setEmail(email);
        users.saveAndFlush(user);
        driver.setVerificationStatus(DriverVerificationStatus.PENDING_REVIEW);
        return drivers.saveAndFlush(driver);
    }

    private UUID document(Driver driver, DocumentType type, String status) {
        return jdbc.queryForObject("INSERT INTO driver_documents (driver_id, type, storage_key, status) "
                + "VALUES (?, ?, ?, ?) RETURNING id", UUID.class, driver.getId(), type.name(), "k-" + UUID.randomUUID(), status);
    }

    private Vehicle vehicle(Driver driver) {
        Vehicle v = newVehicle(driver);
        v.setRegistrationNumber("JK05" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return vehicles.saveAndFlush(v);
    }

    private void readyForApproval(Driver driver) {
        document(driver, DocumentType.VEHICLE_REGISTRATION, "APPROVED");
        document(driver, DocumentType.SELFIE, "APPROVED");
        Vehicle v = vehicle(driver);
        v.setVerificationStatus(VehicleVerificationStatus.APPROVED);
        vehicles.saveAndFlush(v);
        acceptCurrentDeclaration(SubjectType.DRIVER, driver.getId());
    }

    private String sentBody(String to) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender, timeout(5000)).send(eq(to), anyString(), body.capture());
        return body.getValue();
    }

    @Test
    void approvingACaptainSendsAWelcomeAfterTheDecisionCommits() {
        String to = "welcome-" + UUID.randomUUID() + "@example.test";
        Driver driver = captain(to);
        readyForApproval(driver);
        when(emailSender.send(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            // The row must already be committed and visible from another connection when the email goes out.
            assertThat(jdbc.queryForObject("SELECT verification_status FROM drivers WHERE id = ?", String.class,
                    driver.getId())).isEqualTo("APPROVED");
            return true;
        });

        service.approveDriver(driver.getId(), admin(), "ADMIN", "127.0.0.1");

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender, timeout(5000)).send(eq(to), subject.capture(), body.capture());
        assertThat(subject.getValue()).containsIgnoringCase("approved");
        assertThat(body.getValue()).contains("Asma").contains("go online").contains("supportladygo@gmail.com");
    }

    @Test
    void rejectingACaptainSendsThePlainReasonExactlyAsWritten() {
        String to = "reject-" + UUID.randomUUID() + "@example.test";
        Driver driver = captain(to);

        service.rejectDriver(driver.getId(), admin(), "ADMIN", REASON, "127.0.0.1");

        String body = sentBody(to);
        assertThat(body).contains(REASON); // free text rendered verbatim as plain text, never as markup
        assertThat(body).contains("supportladygo@gmail.com");
    }

    @Test
    void rejectingADocumentNamesTheItemQuotesTheReasonAndSaysHowToResubmit() {
        String to = "doc-" + UUID.randomUUID() + "@example.test";
        Driver driver = captain(to);
        UUID docId = document(driver, DocumentType.VEHICLE_REGISTRATION, "PENDING");

        service.rejectDocument(docId, admin(), "ADMIN", REASON, "127.0.0.1");

        String body = sentBody(to);
        assertThat(body).contains(REASON).containsIgnoringCase("RC").containsIgnoringCase("upload");
    }

    @Test
    void rejectingAVehicleNamesTheVehicleAndQuotesTheReason() {
        String to = "veh-" + UUID.randomUUID() + "@example.test";
        Driver driver = captain(to);
        Vehicle vehicle = vehicle(driver);

        service.rejectVehicle(vehicle.getId(), admin(), "ADMIN", REASON, "127.0.0.1");

        assertThat(sentBody(to)).contains(REASON).containsIgnoringCase("vehicle");
    }

    @Test
    void approvingADocumentOrVehicleSendsNothing() throws Exception {
        String to = "quiet-" + UUID.randomUUID() + "@example.test";
        Driver driver = captain(to);
        UUID docId = document(driver, DocumentType.SELFIE, "PENDING");
        Vehicle vehicle = vehicle(driver);

        service.approveDocument(docId, admin(), "ADMIN", null, "127.0.0.1");
        service.approveVehicle(vehicle.getId(), admin(), "ADMIN", "127.0.0.1");

        verify(emailSender, after(500).never()).send(eq(to), anyString(), anyString());
    }

    @Test
    void nothingIsSentWhenTheDecisionRollsBack() throws Exception {
        String to = "rollback-" + UUID.randomUUID() + "@example.test";
        Driver driver = captain(to);
        UUID adminId = admin();

        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
            service.rejectDriver(driver.getId(), adminId, "ADMIN", REASON, "127.0.0.1");
            throw new IllegalStateException("something later in the same transaction failed");
        })).isInstanceOf(IllegalStateException.class);

        verify(emailSender, after(700).never()).send(anyString(), anyString(), anyString());
        assertThat(jdbc.queryForObject("SELECT verification_status FROM drivers WHERE id = ?", String.class,
                driver.getId())).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void aFailingEmailProviderNeverUndoesOrFailsTheDecision() {
        String to = "boom-" + UUID.randomUUID() + "@example.test";
        Driver driver = captain(to);
        when(emailSender.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("smtp down " + to));

        service.rejectDriver(driver.getId(), admin(), "ADMIN", REASON, "127.0.0.1");

        verify(emailSender, timeout(5000)).send(eq(to), anyString(), anyString());
        assertThat(jdbc.queryForObject("SELECT verification_status FROM drivers WHERE id = ?", String.class,
                driver.getId())).isEqualTo("REJECTED");
    }

    @Test
    void aProviderThatReportsFailureByReturningFalseAlsoLeavesTheDecisionAlone() {
        String to = "false-" + UUID.randomUUID() + "@example.test";
        Driver driver = captain(to);
        when(emailSender.send(anyString(), anyString(), anyString())).thenReturn(false);

        service.rejectDriver(driver.getId(), admin(), "ADMIN", REASON, "127.0.0.1");

        verify(emailSender, timeout(5000)).send(eq(to), anyString(), anyString());
        assertThat(jdbc.queryForObject("SELECT verification_status FROM drivers WHERE id = ?", String.class,
                driver.getId())).isEqualTo("REJECTED");
    }

    @Test
    void aCaptainWithoutABoundEmailIsSimplyNotEmailed() throws Exception {
        Driver driver = captain(null);

        service.rejectDriver(driver.getId(), admin(), "ADMIN", REASON, "127.0.0.1");

        verify(emailSender, after(500).never()).send(anyString(), anyString(), anyString());
    }
}
