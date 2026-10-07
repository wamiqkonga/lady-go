package com.ladygo.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.customers.domain.Customer;
import com.ladygo.drivers.domain.DocumentStatus;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverDocument;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.drivers.repository.DriverDocumentRepository;
import com.ladygo.drivers.service.DriverReverificationService;
import com.ladygo.locations.domain.City;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.UserRole;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.domain.VehicleVerificationStatus;
import com.ladygo.verification.domain.EligibilityReportStatus;
import com.ladygo.verification.domain.SubjectType;
import com.ladygo.verification.repository.EligibilityReportRepository;
import com.ladygo.verification.service.EligibilityReportService;
import com.ladygo.verification.service.SelfDeclarationService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

class AdminVerificationServiceTest extends PersistenceTestSupport {

    @Autowired
    private AdminVerificationService service;
    @Autowired
    private DriverDocumentRepository documents;
    @Autowired
    private AuditLogRepository auditLogs;
    @Autowired
    private DriverReverificationService reverificationService;
    @Autowired
    private EligibilityReportService eligibilityReportService;
    @Autowired
    private EligibilityReportRepository eligibilityReports;
    @Autowired
    private SelfDeclarationService selfDeclarations;

    private UUID newAdminUserId() {
        return newUser(UserRole.ADMIN).getId();
    }

    private DriverDocument pendingDocument(Driver driver, DocumentType type) {
        DriverDocument document = new DriverDocument();
        document.setDriverId(driver.getId());
        document.setType(type);
        document.setStorageKey("k-" + UUID.randomUUID());
        return documents.saveAndFlush(document);
    }

    @Test
    void listPendingDocumentsReturnsOnlyPending() {
        Driver driver = newDriver(newCity());
        pendingDocument(driver, DocumentType.LICENSE);

        var page = service.listPendingDocuments(PageRequest.of(0, 10));

        assertThat(page.content()).extracting("driverId").contains(driver.getId());
    }

    @Test
    void approvingADocumentWritesAnAuditLogEntry() {
        Driver driver = newDriver(newCity());
        DriverDocument document = pendingDocument(driver, DocumentType.LICENSE);
        UUID adminId = newAdminUserId();

        service.approveDocument(document.getId(), adminId, "ADMIN", "looks good", "127.0.0.1");

        DriverDocument reloaded = documents.findById(document.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DocumentStatus.APPROVED);
        assertThat(reloaded.getReviewerId()).isEqualTo(adminId);
        assertThat(auditLogs.findAll()).anySatisfy(entry -> {
            assertThat(entry.getAction()).isEqualTo("DRIVER_DOCUMENT_APPROVED");
            assertThat(entry.getTargetId()).isEqualTo(document.getId().toString());
        });
    }

    @Test
    void approvingASelfieClearsAPendingReverification() {
        Driver driver = newDriver(newCity());
        reverificationService.blockImmediatelyForReport(driver.getId());
        DriverDocument selfie = pendingDocument(driver, DocumentType.SELFIE);

        service.approveDocument(selfie.getId(), newAdminUserId(), "ADMIN", null, "127.0.0.1");

        assertThat(reverificationService.isBlockedFromGoingOnline(driver.getId(), Instant.now())).isFalse();
    }

    @Test
    void rejectingWithoutANoteIsRejected() {
        Driver driver = newDriver(newCity());
        DriverDocument document = pendingDocument(driver, DocumentType.LICENSE);

        assertThatThrownBy(() -> service.rejectDocument(document.getId(), newAdminUserId(), "ADMIN", "", "127.0.0.1"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectingWithANoteWritesAnAuditLogEntry() {
        Driver driver = newDriver(newCity());
        DriverDocument document = pendingDocument(driver, DocumentType.LICENSE);

        service.rejectDocument(document.getId(), newAdminUserId(), "ADMIN", "blurry photo", "127.0.0.1");

        DriverDocument reloaded = documents.findById(document.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DocumentStatus.REJECTED);
        assertThat(auditLogs.findAll()).anySatisfy(entry -> assertThat(entry.getAction()).isEqualTo("DRIVER_DOCUMENT_REJECTED"));
    }

    @Test
    void resolvingAReportWritesAnAuditLogEntry() {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        var booking = newBooking(customer, city, com.ladygo.bookings.domain.BookingStatus.DRIVER_ASSIGNED, driver.getId());
        var report = eligibilityReportService.report(customer.getUserId(), booking.getId(), "concern");

        service.resolveReport(report.id(), EligibilityReportStatus.DISMISSED, newAdminUserId(), "ADMIN", "no issue", "127.0.0.1");

        assertThat(eligibilityReports.findById(report.id()).orElseThrow().getStatus())
                .isEqualTo(EligibilityReportStatus.DISMISSED);
        assertThat(auditLogs.findAll()).anySatisfy(entry -> assertThat(entry.getAction()).isEqualTo("ELIGIBILITY_REPORT_DISMISSED"));
    }

    @Test
    void resolvingToOpenIsRejected() {
        City city = newCity();
        Customer customer = newCustomer();
        Driver driver = newDriver(city);
        var booking = newBooking(customer, city, com.ladygo.bookings.domain.BookingStatus.DRIVER_ASSIGNED, driver.getId());
        var report = eligibilityReportService.report(customer.getUserId(), booking.getId(), "concern");

        assertThatThrownBy(() -> service.resolveReport(
                report.id(), EligibilityReportStatus.OPEN, newAdminUserId(), "ADMIN", null, "127.0.0.1"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void triggeringReverificationWritesAnAuditLogEntry() {
        Driver driver = newDriver(newCity());

        service.triggerRandomReverification(driver.getId(), UUID.randomUUID(), "ADMIN", "127.0.0.1");

        assertThat(auditLogs.findAll()).anySatisfy(
                entry -> assertThat(entry.getAction()).isEqualTo("DRIVER_RANDOM_REVERIFICATION_TRIGGERED"));
    }

    /**
     * The actual Phase 5 exit criteria: "admin approval path works end to end." Every required piece
     * (licence, vehicle registration, vehicle photo, insurance, selfie, vehicle approval, declaration)
     * gets approved/accepted, then the driver-as-a-whole decision flips verificationStatus to
     * APPROVED - the same status the pre-existing ck_drivers_online_requires_approval constraint
     * requires before a driver can ever go ONLINE (Phase 6).
     */
    @Test
    void theFullApprovalPathWorksEndToEnd() {
        Driver driver = newDriver(newCity());
        Vehicle vehicle = newVehicle(driver);
        selfDeclarations.accept(SubjectType.DRIVER, driver.getId(), "v1");
        UUID adminId = newAdminUserId();

        for (DocumentType type : List.of(DocumentType.LICENSE, DocumentType.VEHICLE_REGISTRATION,
                DocumentType.VEHICLE_PHOTO, DocumentType.INSURANCE, DocumentType.SELFIE)) {
            DriverDocument document = pendingDocument(driver, type);
            service.approveDocument(document.getId(), adminId, "ADMIN", "ok", "127.0.0.1");
        }
        service.approveVehicle(vehicle.getId(), adminId, "ADMIN", "127.0.0.1");

        var review = service.getDriverReview(driver.getId());
        assertThat(review.readyForApproval()).isTrue();
        assertThat(review.missingRequirements()).isEmpty();

        service.approveDriver(driver.getId(), adminId, "ADMIN", "127.0.0.1");

        Driver reloaded = drivers.findById(driver.getId()).orElseThrow();
        assertThat(reloaded.getVerificationStatus()).isEqualTo(DriverVerificationStatus.APPROVED);
        assertThat(reloaded.getVerifiedBy()).isEqualTo(adminId);
        assertThat(auditLogs.findAll()).anySatisfy(entry -> assertThat(entry.getAction()).isEqualTo("DRIVER_APPROVED"));
    }

    @Test
    void approvingAnIncompleteDriverIsRejectedWithWhatIsMissing() {
        Driver driver = newDriver(newCity());

        assertThatThrownBy(() -> service.approveDriver(driver.getId(), newAdminUserId(), "ADMIN", "127.0.0.1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("LICENSE");

        Driver reloaded = drivers.findById(driver.getId()).orElseThrow();
        assertThat(reloaded.getVerificationStatus()).isNotEqualTo(DriverVerificationStatus.APPROVED);
    }

    @Test
    void rejectingADriverRequiresANoteAndWritesAnAuditLogEntry() {
        Driver driver = newDriver(newCity());

        assertThatThrownBy(() -> service.rejectDriver(driver.getId(), newAdminUserId(), "ADMIN", "", "127.0.0.1"))
                .isInstanceOf(ValidationException.class);

        service.rejectDriver(driver.getId(), newAdminUserId(), "ADMIN", "fake licence", "127.0.0.1");

        Driver reloaded = drivers.findById(driver.getId()).orElseThrow();
        assertThat(reloaded.getVerificationStatus()).isEqualTo(DriverVerificationStatus.REJECTED);
        assertThat(reloaded.getRejectionReason()).isEqualTo("fake licence");
        assertThat(auditLogs.findAll()).anySatisfy(entry -> assertThat(entry.getAction()).isEqualTo("DRIVER_REJECTED"));
    }

    @Test
    void approvingAVehicleWritesAnAuditLogEntry() {
        Driver driver = newDriver(newCity());
        Vehicle vehicle = newVehicle(driver);

        service.approveVehicle(vehicle.getId(), newAdminUserId(), "ADMIN", "127.0.0.1");

        assertThat(vehicles.findById(vehicle.getId()).orElseThrow().getVerificationStatus())
                .isEqualTo(VehicleVerificationStatus.APPROVED);
        assertThat(auditLogs.findAll()).anySatisfy(entry -> assertThat(entry.getAction()).isEqualTo("VEHICLE_APPROVED"));
    }

    @Test
    void policeVerificationIsOnlyRequiredWhenTheConfigFlagIsOn() {
        jdbc.update("UPDATE configuration SET value = 'true' WHERE key = 'driver.police_verification.required'");
        Driver driver = newDriver(newCity());
        Vehicle vehicle = newVehicle(driver);
        selfDeclarations.accept(SubjectType.DRIVER, driver.getId(), "v1");
        UUID adminId = newAdminUserId();
        for (DocumentType type : List.of(DocumentType.LICENSE, DocumentType.VEHICLE_REGISTRATION,
                DocumentType.VEHICLE_PHOTO, DocumentType.INSURANCE, DocumentType.SELFIE)) {
            service.approveDocument(pendingDocument(driver, type).getId(), adminId, "ADMIN", "ok", "127.0.0.1");
        }
        service.approveVehicle(vehicle.getId(), adminId, "ADMIN", "127.0.0.1");

        assertThatThrownBy(() -> service.approveDriver(driver.getId(), adminId, "ADMIN", "127.0.0.1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("POLICE_VERIFICATION");

        service.approveDocument(pendingDocument(driver, DocumentType.POLICE_VERIFICATION).getId(),
                adminId, "ADMIN", "ok", "127.0.0.1");
        service.approveDriver(driver.getId(), adminId, "ADMIN", "127.0.0.1");

        assertThat(drivers.findById(driver.getId()).orElseThrow().getVerificationStatus())
                .isEqualTo(DriverVerificationStatus.APPROVED);
    }

    /** Adversarial review finding, MEDIUM: an approved-but-now-expired document (insurance is the one
     *  type that actually carries an expiry today) previously passed approval silently - the gate only
     *  ever checked {@code status == APPROVED}, never {@code expiresAt}. */
    @Test
    void anApprovedButExpiredDocumentBlocksDriverApproval() {
        Driver driver = newDriver(newCity());
        Vehicle vehicle = newVehicle(driver);
        selfDeclarations.accept(SubjectType.DRIVER, driver.getId(), "v1");
        UUID adminId = newAdminUserId();

        for (DocumentType type : List.of(DocumentType.LICENSE, DocumentType.VEHICLE_REGISTRATION,
                DocumentType.VEHICLE_PHOTO, DocumentType.SELFIE)) {
            service.approveDocument(pendingDocument(driver, type).getId(), adminId, "ADMIN", "ok", "127.0.0.1");
        }
        DriverDocument insurance = pendingDocument(driver, DocumentType.INSURANCE);
        insurance.setExpiresAt(LocalDate.now().minusDays(1));
        documents.saveAndFlush(insurance);
        service.approveDocument(insurance.getId(), adminId, "ADMIN", "ok", "127.0.0.1");
        service.approveVehicle(vehicle.getId(), adminId, "ADMIN", "127.0.0.1");

        var review = service.getDriverReview(driver.getId());
        assertThat(review.readyForApproval()).isFalse();
        assertThat(review.missingRequirements()).anySatisfy(
                m -> assertThat(m).contains("INSURANCE").contains("expired"));

        assertThatThrownBy(() -> service.approveDriver(driver.getId(), adminId, "ADMIN", "127.0.0.1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("expired");
    }
}
