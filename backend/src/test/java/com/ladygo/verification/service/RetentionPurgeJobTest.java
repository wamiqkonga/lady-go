package com.ladygo.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.drivers.domain.Driver;
import com.ladygo.drivers.domain.DocumentStatus;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.domain.DriverDocument;
import com.ladygo.locations.domain.City;
import com.ladygo.storage.FileStorage;
import com.ladygo.storage.FileStorageException;
import com.ladygo.testsupport.PersistenceTestSupport;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * docs/api/driver-onboarding.md section 7. Adversarial review finding, HIGH: no scheduled job existed
 * at all - these tests fail if {@link RetentionPurgeJob} is removed or stops deleting the bytes and
 * marking {@code content_deleted_at} once {@code verification.document.retention.days} has elapsed.
 */
class RetentionPurgeJobTest extends PersistenceTestSupport {

    @Autowired
    private RetentionPurgeJob job;
    @Autowired
    private FileStorage fileStorage;

    private DriverDocument newReviewedDocument(Driver driver, Instant reviewedAt) {
        String key = "driver-documents/" + UUID.randomUUID();
        fileStorage.upload(key, new ByteArrayInputStream("content".getBytes()), 7, "image/jpeg");

        DriverDocument document = new DriverDocument();
        document.setDriverId(driver.getId());
        document.setType(DocumentType.LICENSE);
        document.setStorageKey(key);
        document.setStatus(DocumentStatus.APPROVED);
        document.setReviewedAt(reviewedAt);
        em.persist(document);
        em.flush();
        return document;
    }

    private UUID newCapturedRideSelfie(UUID customerId, Instant capturedAt) {
        String key = "ride-selfies/" + UUID.randomUUID();
        fileStorage.upload(key, new ByteArrayInputStream("content".getBytes()), 7, "image/jpeg");
        return jdbc.queryForObject(
                "INSERT INTO ride_selfies (customer_id, selfie_storage_key, captured_at) VALUES (?, ?, ?) RETURNING id",
                UUID.class, customerId, key, java.sql.Timestamp.from(capturedAt));
    }

    @Test
    void aDocumentReviewedPastRetentionHasItsBytesDeletedAndIsMarked() {
        City city = newCity();
        Driver driver = newDriver(city);
        DriverDocument expired = newReviewedDocument(driver, Instant.now().minus(91, ChronoUnit.DAYS));
        String expiredKey = expired.getStorageKey();

        job.purgeExpiredContent();
        flushAndClear();

        DriverDocument reloaded = em.find(DriverDocument.class, expired.getId());
        assertThat(reloaded.getContentDeletedAt()).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo(DocumentStatus.APPROVED); // decision record survives
        assertThatThrownBy(() -> fileStorage.download(expiredKey)).isInstanceOf(FileStorageException.class);
    }

    @Test
    void aRecentlyReviewedDocumentIsLeftUntouched() {
        City city = newCity();
        Driver driver = newDriver(city);
        DriverDocument recent = newReviewedDocument(driver, Instant.now().minus(1, ChronoUnit.DAYS));

        job.purgeExpiredContent();
        flushAndClear();

        DriverDocument reloaded = em.find(DriverDocument.class, recent.getId());
        assertThat(reloaded.getContentDeletedAt()).isNull();
        assertThat(fileStorage.download(recent.getStorageKey())).isNotEmpty(); // still readable
    }

    @Test
    void aRideSelfieCapturedPastRetentionHasItsBytesDeletedAndIsMarked() {
        UUID customerId = newCustomer().getId();
        UUID selfieId = newCapturedRideSelfie(customerId, Instant.now().minus(91, ChronoUnit.DAYS));
        String key = jdbc.queryForObject("SELECT selfie_storage_key FROM ride_selfies WHERE id = ?",
                String.class, selfieId);

        job.purgeExpiredContent();
        flushAndClear();

        Instant contentDeletedAt = jdbc.queryForObject(
                "SELECT content_deleted_at FROM ride_selfies WHERE id = ?", Instant.class, selfieId);
        assertThat(contentDeletedAt).isNotNull();
        assertThatThrownBy(() -> fileStorage.download(key)).isInstanceOf(FileStorageException.class);
    }

    @Test
    void aRecentlyCapturedRideSelfieIsLeftUntouched() {
        UUID customerId = newCustomer().getId();
        UUID selfieId = newCapturedRideSelfie(customerId, Instant.now().minus(1, ChronoUnit.DAYS));
        String key = jdbc.queryForObject("SELECT selfie_storage_key FROM ride_selfies WHERE id = ?",
                String.class, selfieId);

        job.purgeExpiredContent();
        flushAndClear();

        Instant contentDeletedAt = jdbc.queryForObject(
                "SELECT content_deleted_at FROM ride_selfies WHERE id = ?", Instant.class, selfieId);
        assertThat(contentDeletedAt).isNull();
        assertThat(fileStorage.download(key)).isNotEmpty();
    }
}
