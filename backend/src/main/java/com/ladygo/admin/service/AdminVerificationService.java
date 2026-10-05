package com.ladygo.admin.service;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.admin.web.dto.DriverDocumentAdminResponse;
import com.ladygo.admin.web.dto.EligibilityReportAdminResponse;
import com.ladygo.common.api.PageResponse;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.drivers.domain.DocumentStatus;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.domain.DriverDocument;
import com.ladygo.drivers.repository.DriverDocumentRepository;
import com.ladygo.drivers.service.DriverReverificationService;
import com.ladygo.verification.domain.EligibilityReport;
import com.ladygo.verification.domain.EligibilityReportStatus;
import com.ladygo.verification.repository.EligibilityReportRepository;
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

    private final DriverDocumentRepository documents;
    private final EligibilityReportRepository reports;
    private final AuditLogRepository auditLogs;
    private final DriverReverificationService reverificationService;

    public AdminVerificationService(
            DriverDocumentRepository documents, EligibilityReportRepository reports,
            AuditLogRepository auditLogs, DriverReverificationService reverificationService) {
        this.documents = documents;
        this.reports = reports;
        this.auditLogs = auditLogs;
        this.reverificationService = reverificationService;
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
