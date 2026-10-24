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
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserStatus;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * docs/api/driver-onboarding.md section 7, as corrected (the user's own correction of an earlier,
 * incorrect default): rejected submissions purge sooner (30 days) than approved ones (90 days), and an
 * approved+active driver's LICENSE/SELFIE survive indefinitely until 90 days after account closure,
 * not on the normal reviewed-at clock.
 */
class RetentionPurgeJobTest extends PersistenceTestSupport {

    @Autowired
    private RetentionPurgeJob job;
    @Autowired
    private FileStorage fileStorage;

    private DriverDocument newReviewedDocument(
            Driver driver, DocumentType type, DocumentStatus status, Instant reviewedAt) {
        String key = "driver-documents/" + UUID.randomUUID();
        fileStorage.upload(key, new ByteArrayInputStream("content".getBytes()), 7, "image/jpeg");

        DriverDocument document = new DriverDocument();
        document.setDriverId(driver.getId());
        document.setType(type);
        document.setStorageKey(key);
        document.setStatus(status);
        document.setReviewedAt(reviewedAt);
        em.persist(document);
        em.flush();
        return document;
    }

    private void closeAccount(UUID userId, Instant statusChangedAt) {
        User user = em.find(User.class, userId);
        user.setStatus(UserStatus.SUSPENDED);
        user.setStatusChangedAt(statusChangedAt);
        em.flush();
    }

    private UUID newCapturedRideSelfie(UUID customerId, Instant capturedAt) {
        String key = "ride-selfies/" + UUID.randomUUID();
        fileStorage.upload(key, new ByteArrayInputStream("content".getBytes()), 7, "image/jpeg");
        return jdbc.queryForObject(
                "INSERT INTO ride_selfies (customer_id, selfie_storage_key, captured_at) VALUES (?, ?, ?) RETURNING id",
                UUID.class, customerId, key, java.sql.Timestamp.from(capturedAt));
    }

    @Test
    void aRejectedDocumentPastThirtyDaysHasItsBytesDeletedAndIsMarked() {
        Driver driver = newDriver(newCity());
        DriverDocument rejected = newReviewedDocument(
                driver, DocumentType.LICENSE, DocumentStatus.REJECTED, Instant.now().minus(31, ChronoUnit.DAYS));

        job.purgeExpiredContent();
        flushAndClear();

        DriverDocument reloaded = em.find(DriverDocument.class, rejected.getId());
        assertThat(reloaded.getContentDeletedAt()).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo(DocumentStatus.REJECTED); // decision record survives
        assertThatThrownBy(() -> fileStorage.download(rejected.getStorageKey())).isInstanceOf(FileStorageException.class);
    }

    @Test
    void aRejectedDocumentWithinThirtyDaysIsLeftUntouched() {
        Driver driver = newDriver(newCity());
        DriverDocument rejected = newReviewedDocument(
                driver, DocumentType.LICENSE, DocumentStatus.REJECTED, Instant.now().minus(29, ChronoUnit.DAYS));

        job.purgeExpiredContent();
        flushAndClear();

        assertThat(em.find(DriverDocument.class, rejected.getId()).getContentDeletedAt()).isNull();
    }

    @Test
    void anApprovedNonExemptDocumentPastNinetyDaysHasItsBytesDeletedAndIsMarked() {
        Driver driver = newDriver(newCity());
        DriverDocument insurance = newReviewedDocument(
                driver, DocumentType.INSURANCE, DocumentStatus.APPROVED, Instant.now().minus(91, ChronoUnit.DAYS));

        job.purgeExpiredContent();
        flushAndClear();

        DriverDocument reloaded = em.find(DriverDocument.class, insurance.getId());
        assertThat(reloaded.getContentDeletedAt()).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    void anApprovedNonExemptDocumentWithinNinetyDaysIsLeftUntouched() {
        Driver driver = newDriver(newCity());
        DriverDocument insurance = newReviewedDocument(
                driver, DocumentType.INSURANCE, DocumentStatus.APPROVED, Instant.now().minus(89, ChronoUnit.DAYS));

        job.purgeExpiredContent();
        flushAndClear();

        assertThat(em.find(DriverDocument.class, insurance.getId()).getContentDeletedAt()).isNull();
    }

    /** The specific regression the user asked for: an active, approved driver's licence/selfie must
     *  survive the purge no matter how old reviewedAt is - the normal 90-day clock does not apply to
     *  these two document types while the account is active. */
    @Test
    void anActiveApprovedDriversLicenceAndSelfieSurviveNoMatterHowOldTheReview() {
        Driver driver = newDriver(newCity());
        DriverDocument licence = newReviewedDocument(
                driver, DocumentType.LICENSE, DocumentStatus.APPROVED, Instant.now().minus(400, ChronoUnit.DAYS));
        DriverDocument selfie = newReviewedDocument(
                driver, DocumentType.SELFIE, DocumentStatus.APPROVED, Instant.now().minus(400, ChronoUnit.DAYS));

        job.purgeExpiredContent();
        flushAndClear();

        assertThat(em.find(DriverDocument.class, licence.getId()).getContentDeletedAt()).isNull();
        assertThat(em.find(DriverDocument.class, selfie.getId()).getContentDeletedAt()).isNull();
        assertThat(fileStorage.download(licence.getStorageKey())).isNotEmpty();
    }

    @Test
    void aClosedAccountsLicenceIsPurgedNinetyDaysAfterClosure() {
        Driver driver = newDriver(newCity());
        DriverDocument licence = newReviewedDocument(
                driver, DocumentType.LICENSE, DocumentStatus.APPROVED, Instant.now().minus(400, ChronoUnit.DAYS));
        closeAccount(driver.getUserId(), Instant.now().minus(91, ChronoUnit.DAYS));

        job.purgeExpiredContent();
        flushAndClear();

        DriverDocument reloaded = em.find(DriverDocument.class, licence.getId());
        assertThat(reloaded.getContentDeletedAt()).isNotNull();
        assertThatThrownBy(() -> fileStorage.download(licence.getStorageKey())).isInstanceOf(FileStorageException.class);
    }

    @Test
    void aRecentlyClosedAccountsLicenceIsNotYetPurged() {
        Driver driver = newDriver(newCity());
        DriverDocument licence = newReviewedDocument(
                driver, DocumentType.LICENSE, DocumentStatus.APPROVED, Instant.now().minus(400, ChronoUnit.DAYS));
        closeAccount(driver.getUserId(), Instant.now().minus(10, ChronoUnit.DAYS)); // within the 90-day grace

        job.purgeExpiredContent();
        flushAndClear();

        assertThat(em.find(DriverDocument.class, licence.getId()).getContentDeletedAt()).isNull();
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
