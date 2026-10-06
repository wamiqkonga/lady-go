package com.ladygo.admin.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.admin.web.dto.DriverDocumentAdminResponse;
import com.ladygo.admin.web.dto.DriverReviewSummaryResponse;
import com.ladygo.admin.web.dto.EligibilityReportAdminResponse;
import com.ladygo.admin.web.dto.VehicleAdminResponse;
import com.ladygo.common.api.PageResponse;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.drivers.domain.DocumentStatus;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverDocument;
import com.ladygo.drivers.domain.DriverVerificationStatus;
import com.ladygo.drivers.repository.DriverDocumentRepository;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.drivers.service.DriverReverificationService;
import com.ladygo.vehicles.domain.Vehicle;
import com.ladygo.vehicles.domain.VehicleVerificationStatus;
import com.ladygo.vehicles.repository.VehicleRepository;
import com.ladygo.verification.domain.EligibilityReport;
import com.ladygo.verification.domain.EligibilityReportStatus;
import com.ladygo.verification.domain.SubjectType;
import com.ladygo.verification.repository.EligibilityReportRepository;
import com.ladygo.verification.service.SelfDeclarationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin review is driver-documents-and-eligibility-reports only (docs/api/driver-onboarding.md
 * section 3) - there is no customer-verification admin queue, riders were removed from admin review
 * entirely. Every action writes one {@link AuditLog} row via the existing V11 table - no new audit
 * infrastructure.
 */
@Service
public class AdminVerificationService {

    private static final List<DocumentType> ALWAYS_REQUIRED = List.of(
            DocumentType.LICENSE, DocumentType.VEHICLE_REGISTRATION, DocumentType.VEHICLE_PHOTO,
            DocumentType.INSURANCE, DocumentType.SELFIE);

    private final DriverDocumentRepository documents;
    private final EligibilityReportRepository reports;
    private final AuditLogRepository auditLogs;
    private final DriverReverificationService reverificationService;
    private final DriverRepository drivers;
    private final VehicleRepository vehicles;
    private final SelfDeclarationService selfDeclarations;
    private final ConfigurationService config;

    public AdminVerificationService(
            DriverDocumentRepository documents, EligibilityReportRepository reports,
            AuditLogRepository auditLogs, DriverReverificationService reverificationService,
            DriverRepository drivers, VehicleRepository vehicles, SelfDeclarationService selfDeclarations,
            ConfigurationService config) {
        this.documents = documents;
        this.reports = reports;
        this.auditLogs = auditLogs;
        this.reverificationService = reverificationService;
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.selfDeclarations = selfDeclarations;
        this.config = config;
    }

    public PageResponse<DriverDocumentAdminResponse> listPendingDocuments(Pageable pageable) {
        Page<DriverDocument> page = documents.findByStatusOrderByCreatedAtAsc(DocumentStatus.PENDING, pageable);
        return PageResponse.of(page.map(AdminVerificationService::toDocumentResponse));
    }

    @Transactional
    public DriverDocumentAdminResponse approveDocument(
            UUID documentId, UUID adminUserId, String adminRole, String note, String ip) {
        DriverDocument document = documents.findById(documentId).orElseThrow(ResourceNotFoundException::new);
        document.setStatus(DocumentStatus.APPROVED);
        document.setReviewerId(adminUserId);
        document.setReviewNote(note);
        document.setReviewedAt(Instant.now());
        DriverDocument saved = documents.save(document);

        if (saved.getType() == DocumentType.SELFIE) {
            reverificationService.clearOnApprovedSelfie(saved.getDriverId());
        }
        audit(adminUserId, adminRole, "DRIVER_DOCUMENT_APPROVED", "driver_documents", documentId, ip);
        return toDocumentResponse(saved);
    }

    @Transactional
    public DriverDocumentAdminResponse rejectDocument(
            UUID documentId, UUID adminUserId, String adminRole, String note, String ip) {
        if (note == null || note.isBlank()) {
            throw new ValidationException("A note is required to reject a document.");
        }
        DriverDocument document = documents.findById(documentId).orElseThrow(ResourceNotFoundException::new);
        document.setStatus(DocumentStatus.REJECTED);
        document.setReviewerId(adminUserId);
        document.setReviewNote(note);
        document.setReviewedAt(Instant.now());
        DriverDocument saved = documents.save(document);

        audit(adminUserId, adminRole, "DRIVER_DOCUMENT_REJECTED", "driver_documents", documentId, ip);
        return toDocumentResponse(saved);
    }

    public PageResponse<EligibilityReportAdminResponse> listOpenReports(Pageable pageable) {
        Page<EligibilityReport> page = reports.findByStatusOrderByCreatedAtAsc(EligibilityReportStatus.OPEN, pageable);
        return PageResponse.of(page.map(AdminVerificationService::toReportResponse));
    }

    @Transactional
    public EligibilityReportAdminResponse resolveReport(
            UUID reportId, EligibilityReportStatus status, UUID adminUserId, String adminRole, String note, String ip) {
        if (status == EligibilityReportStatus.OPEN) {
            throw new ValidationException("status must be REVIEWED, DISMISSED, or ACTIONED.");
        }
        EligibilityReport report = reports.findById(reportId).orElseThrow(ResourceNotFoundException::new);
        report.setStatus(status);
        report.setReviewerId(adminUserId);
        report.setReviewNote(note);
        report.setReviewedAt(Instant.now());
        EligibilityReport saved = reports.save(report);

        audit(adminUserId, adminRole, "ELIGIBILITY_REPORT_" + status.name(), "eligibility_reports", reportId, ip);
        return toReportResponse(saved);
    }

    @Transactional
    public void triggerRandomReverification(UUID driverId, UUID adminUserId, String adminRole, String ip) {
        reverificationService.triggerRandomReverification(driverId);
        audit(adminUserId, adminRole, "DRIVER_RANDOM_REVERIFICATION_TRIGGERED", "drivers", driverId, ip);
    }

    /**
     * Everything an admin needs to decide on a driver, in one view - the actual "admin approval path"
     * (Phase 5 exit criteria) reads this before calling {@link #approveDriver}.
     */
    public DriverReviewSummaryResponse getDriverReview(UUID driverId) {
        Driver driver = drivers.findById(driverId).orElseThrow(ResourceNotFoundException::new);
        Vehicle vehicle = vehicles.findByDriverId(driverId).stream().findFirst().orElse(null);
        boolean declarationAccepted = selfDeclarations.hasAcceptedCurrentVersion(SubjectType.DRIVER, driverId);

        List<DriverDocumentAdminResponse> latestPerType = requiredTypes().stream()
                .map(type -> documents.findFirstByDriverIdAndTypeOrderByCreatedAtDesc(driverId, type))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .map(AdminVerificationService::toDocumentResponse)
                .toList();

        List<String> missing = missingRequirements(driverId, vehicle, declarationAccepted);
        VehicleAdminResponse vehicleResponse = vehicle == null ? null : new VehicleAdminResponse(
                vehicle.getId(), vehicle.getRegistrationNumber(), vehicle.getVehicleType(), vehicle.getVerificationStatus());

        return new DriverReviewSummaryResponse(driverId, driver.getVerificationStatus(), declarationAccepted,
                vehicleResponse, latestPerType, missing.isEmpty(), missing);
    }

    @Transactional
    public void approveDriver(UUID driverId, UUID adminUserId, String adminRole, String ip) {
        Driver driver = drivers.findById(driverId).orElseThrow(ResourceNotFoundException::new);
        Vehicle vehicle = vehicles.findByDriverId(driverId).stream().findFirst().orElse(null);
        boolean declarationAccepted = selfDeclarations.hasAcceptedCurrentVersion(SubjectType.DRIVER, driverId);
        List<String> missing = missingRequirements(driverId, vehicle, declarationAccepted);
        if (!missing.isEmpty()) {
            throw new ValidationException("Driver is not ready for approval: " + String.join(", ", missing));
        }

        driver.setVerificationStatus(DriverVerificationStatus.APPROVED);
        driver.setVerifiedBy(adminUserId);
        driver.setVerifiedAt(Instant.now());
        driver.setRejectionReason(null);
        drivers.save(driver);

        audit(adminUserId, adminRole, "DRIVER_APPROVED", "drivers", driverId, ip);
    }

    @Transactional
    public void rejectDriver(UUID driverId, UUID adminUserId, String adminRole, String note, String ip) {
        if (note == null || note.isBlank()) {
            throw new ValidationException("A note is required to reject a driver.");
        }
        Driver driver = drivers.findById(driverId).orElseThrow(ResourceNotFoundException::new);
        driver.setVerificationStatus(DriverVerificationStatus.REJECTED);
        driver.setVerifiedBy(adminUserId);
        driver.setVerifiedAt(Instant.now());
        driver.setRejectionReason(note);
        drivers.save(driver);

        audit(adminUserId, adminRole, "DRIVER_REJECTED", "drivers", driverId, ip);
    }

    @Transactional
    public void approveVehicle(UUID vehicleId, UUID adminUserId, String adminRole, String ip) {
        Vehicle vehicle = vehicles.findById(vehicleId).orElseThrow(ResourceNotFoundException::new);
        vehicle.setVerificationStatus(VehicleVerificationStatus.APPROVED);
        vehicles.save(vehicle);

        audit(adminUserId, adminRole, "VEHICLE_APPROVED", "vehicles", vehicleId, ip);
    }

    @Transactional
    public void rejectVehicle(UUID vehicleId, UUID adminUserId, String adminRole, String note, String ip) {
        if (note == null || note.isBlank()) {
            throw new ValidationException("A note is required to reject a vehicle.");
        }
        Vehicle vehicle = vehicles.findById(vehicleId).orElseThrow(ResourceNotFoundException::new);
        vehicle.setVerificationStatus(VehicleVerificationStatus.REJECTED);
        vehicles.save(vehicle);

        audit(adminUserId, adminRole, "VEHICLE_REJECTED", "vehicles", vehicleId, ip);
    }

    private List<String> missingRequirements(UUID driverId, Vehicle vehicle, boolean declarationAccepted) {
        List<String> missing = new ArrayList<>();
        for (DocumentType type : requiredTypes()) {
            boolean approved = documents.findFirstByDriverIdAndTypeOrderByCreatedAtDesc(driverId, type)
                    .map(d -> d.getStatus() == DocumentStatus.APPROVED)
                    .orElse(false);
            if (!approved) {
                missing.add(type.name() + " not approved");
            }
        }
        if (vehicle == null) {
            missing.add("no vehicle submitted");
        } else if (vehicle.getVerificationStatus() != VehicleVerificationStatus.APPROVED) {
            missing.add("vehicle not approved");
        }
        if (!declarationAccepted) {
            missing.add("self-declaration not accepted");
        }
        return missing;
    }

    /** Police verification is only required once flipped on before public launch (docs/api/driver-onboarding.md section 3). */
    private List<DocumentType> requiredTypes() {
        boolean policeRequired = config.getBoolean("driver.police_verification.required", false);
        if (!policeRequired) {
            return ALWAYS_REQUIRED;
        }
        List<DocumentType> withPolice = new ArrayList<>(ALWAYS_REQUIRED);
        withPolice.add(DocumentType.POLICE_VERIFICATION);
        return withPolice;
    }

    private void audit(UUID actorId, String actorRole, String action, String targetType, UUID targetId, String ip) {
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setActorRole(actorRole);
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId.toString());
        entry.setIp(ip);
        auditLogs.save(entry);
    }

    private static DriverDocumentAdminResponse toDocumentResponse(DriverDocument document) {
        return new DriverDocumentAdminResponse(document.getId(), document.getDriverId(), document.getType(),
                document.getStatus(), document.getReason(), document.getCreatedAt());
    }

    private static EligibilityReportAdminResponse toReportResponse(EligibilityReport report) {
        return new EligibilityReportAdminResponse(report.getId(), report.getBookingId(), report.getReporterId(),
                report.getSubjectType(), report.getSubjectId(), report.getReason(), report.isAutoCreated(),
                report.getStatus(), report.getCreatedAt());
    }
}
