package com.ladygo.verification.service;

import java.time.Duration;
import java.time.Instant;
import com.ladygo.config.ConfigurationService;
import com.ladygo.drivers.domain.DriverDocument;
import com.ladygo.drivers.repository.DriverDocumentRepository;
import com.ladygo.storage.FileStorage;
import com.ladygo.verification.domain.RideSelfie;
import com.ladygo.verification.repository.RideSelfieRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * docs/api/driver-onboarding.md section 7 - deletes the raw bytes of reviewed driver documents and
 * captured ride selfies once {@code verification.document.retention.days} has passed, keeping the
 * decision record (status, reviewer, timestamps) and only setting {@code content_deleted_at}.
 * Adversarial review finding, HIGH: this job did not exist at all - the retention columns
 * ({@code content_deleted_at}) and config key were seeded by the Phase 5 migration but nothing ever
 * read or wrote them, so sensitive images were retained indefinitely with no deletion mechanism.
 */
@Component
public class RetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeJob.class);

    private final DriverDocumentRepository documents;
    private final RideSelfieRepository rideSelfies;
    private final FileStorage fileStorage;
    private final ConfigurationService config;

    public RetentionPurgeJob(
            DriverDocumentRepository documents, RideSelfieRepository rideSelfies,
            FileStorage fileStorage, ConfigurationService config) {
        this.documents = documents;
        this.rideSelfies = rideSelfies;
        this.fileStorage = fileStorage;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${ladygo.retention.purge.interval-ms:3600000}")
    public void run() {
        purgeExpiredContent();
    }

    @Transactional
    public void purgeExpiredContent() {
        Duration retention = config.getDays("verification.document.retention.days", Duration.ofDays(90));
        Instant cutoff = Instant.now().minus(retention);
        purgeDocuments(cutoff);
        purgeRideSelfies(cutoff);
    }

    private void purgeDocuments(Instant cutoff) {
        for (DriverDocument document : documents.findByReviewedAtBeforeAndContentDeletedAtIsNull(cutoff)) {
            deleteQuietly(document.getStorageKey());
            document.setContentDeletedAt(Instant.now());
            documents.save(document);
        }
    }

    private void purgeRideSelfies(Instant cutoff) {
        for (RideSelfie selfie : rideSelfies.findByCapturedAtBeforeAndContentDeletedAtIsNull(cutoff)) {
            deleteQuietly(selfie.getSelfieStorageKey());
            selfie.setContentDeletedAt(Instant.now());
            rideSelfies.save(selfie);
        }
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
