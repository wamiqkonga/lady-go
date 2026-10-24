package com.ladygo.verification.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import com.ladygo.config.ConfigurationService;
import com.ladygo.drivers.domain.DocumentStatus;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DriverDocument;
import com.ladygo.drivers.repository.DriverDocumentRepository;
import com.ladygo.drivers.repository.DriverRepository;
import com.ladygo.storage.FileStorage;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserStatus;
import com.ladygo.users.repository.UserRepository;
import com.ladygo.verification.domain.RideSelfie;
import com.ladygo.verification.repository.RideSelfieRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * docs/api/driver-onboarding.md section 7, as corrected: a rejected submission's bytes are purged 30
 * days after review; an approved document's bytes are purged 90 days after review - EXCEPT a licence or
 * reference selfie belonging to a driver who is still APPROVED and whose account is still ACTIVE, which
 * survive until 90 days after account closure instead. Ride selfies are purged on their own clock from
 * capture, with no review step. All four periods are independently configurable.
 *
 * <p>No account-closure feature exists yet - nothing in this codebase ever sets {@code User.status}
 * away from {@code ACTIVE} - so the closure branch is dormant until that ships, same as the Phase 6
 * re-verification fields. {@link User#getStatusChangedAt()} is the anchor it will use.
 */
@Component
public class RetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeJob.class);

    /** Exempt from the normal reviewed-at clock while the driver is active+approved (docs section 7). */
    private static final List<DocumentType> CLOSURE_EXEMPT_TYPES = List.of(DocumentType.LICENSE, DocumentType.SELFIE);

    private final DriverDocumentRepository documents;
    private final RideSelfieRepository rideSelfies;
    private final DriverRepository drivers;
    private final UserRepository users;
    private final FileStorage fileStorage;
    private final ConfigurationService config;

    public RetentionPurgeJob(
            DriverDocumentRepository documents, RideSelfieRepository rideSelfies, DriverRepository drivers,
            UserRepository users, FileStorage fileStorage, ConfigurationService config) {
        this.documents = documents;
        this.rideSelfies = rideSelfies;
        this.drivers = drivers;
        this.users = users;
        this.fileStorage = fileStorage;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${ladygo.retention.purge.interval-ms:3600000}")
    public void run() {
        purgeExpiredContent();
    }

    @Transactional
    public void purgeExpiredContent() {
        purgeRejectedDocuments();
        purgeApprovedNonExemptDocuments();
        purgeClosedAccountExemptDocuments();
        purgeRideSelfies();
    }

    private void purgeRejectedDocuments() {
        Instant cutoff = Instant.now().minus(
                config.getDays("verification.document.rejected_retention.days", Duration.ofDays(30)));
        for (DriverDocument document : documents.findByStatusAndReviewedAtBeforeAndContentDeletedAtIsNull(
                DocumentStatus.REJECTED, cutoff)) {
            purge(document);
        }
    }

    private void purgeApprovedNonExemptDocuments() {
        Instant cutoff = Instant.now().minus(
                config.getDays("verification.document.approved_retention.days", Duration.ofDays(90)));
        for (DriverDocument document : documents.findByStatusAndReviewedAtBeforeAndContentDeletedAtIsNull(
                DocumentStatus.APPROVED, cutoff)) {
            if (!CLOSURE_EXEMPT_TYPES.contains(document.getType())) {
                purge(document);
            }
        }
    }

    private void purgeClosedAccountExemptDocuments() {
        Duration closureRetention = config.getDays("verification.driver_closure_retention.days", Duration.ofDays(90));
        for (DriverDocument document : documents.findByStatusAndTypeInAndContentDeletedAtIsNull(
                DocumentStatus.APPROVED, CLOSURE_EXEMPT_TYPES)) {
            if (isPastClosureRetention(document, closureRetention)) {
                purge(document);
            }
        }
    }

    private boolean isPastClosureRetention(DriverDocument document, Duration closureRetention) {
        Driver driver = drivers.findById(document.getDriverId()).orElse(null);
        if (driver == null) {
            return false;
        }
        User user = users.findById(driver.getUserId()).orElse(null);
        if (user == null || user.getStatus() == UserStatus.ACTIVE || user.getStatusChangedAt() == null) {
            return false; // still active (or no closure timestamp recorded yet) - never purge.
        }
        return user.getStatusChangedAt().isBefore(Instant.now().minus(closureRetention));
    }

    private void purgeRideSelfies() {
        Instant cutoff = Instant.now().minus(
                config.getDays("verification.ride_selfie.retention.days", Duration.ofDays(90)));
        for (RideSelfie selfie : rideSelfies.findByCapturedAtBeforeAndContentDeletedAtIsNull(cutoff)) {
            deleteQuietly(selfie.getSelfieStorageKey());
            selfie.setContentDeletedAt(Instant.now());
            rideSelfies.save(selfie);
        }
    }

    private void purge(DriverDocument document) {
        deleteQuietly(document.getStorageKey());
        document.setContentDeletedAt(Instant.now());
        documents.save(document);
    }

    /** Missing bytes (already deleted, or never actually written) must not block marking the row
     *  purged - the row itself is the retention record, not the storage call's success. */
    private void deleteQuietly(String storageKey) {
        try {
            fileStorage.delete(storageKey);
        } catch (RuntimeException ex) {
            log.warn("operation=retention_purge_delete_failed storageKey={} error={}", storageKey, ex.getMessage());
        }
    }
}
