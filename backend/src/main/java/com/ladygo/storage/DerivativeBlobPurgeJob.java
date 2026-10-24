package com.ladygo.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import com.ladygo.config.ConfigurationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Backstop for {@code AdminVerificationService.viewDocumentUrl}'s in-process scheduled delete of a
 * watermarked derivative - that delete is lost if the app restarts between creating the derivative and
 * the scheduled time firing, which would otherwise leave a sensitive PII copy (a face, a licence
 * number) in storage forever with no other purge path (unlike {@code DriverDocument}/{@code RideSelfie}
 * rows, a derivative is never recorded in the database at all). This sweep catches anything the
 * in-process delete missed, independent of why it was missed.
 */
@Component
public class DerivativeBlobPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(DerivativeBlobPurgeJob.class);
    private static final String DERIVATIVE_PREFIX = "driver-document-derivatives/";

    private final FileStorage fileStorage;
    private final ConfigurationService config;

    public DerivativeBlobPurgeJob(FileStorage fileStorage, ConfigurationService config) {
        this.fileStorage = fileStorage;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${ladygo.retention.derivative_purge.interval-ms:3600000}")
    public void run() {
        sweep();
    }

    public void sweep() {
        Duration maxAge = config.getHours("storage.derivative.max_age.hours", Duration.ofHours(24));
        Instant cutoff = Instant.now().minus(maxAge);
        List<String> orphaned = fileStorage.listKeysOlderThan(DERIVATIVE_PREFIX, cutoff);
        for (String key : orphaned) {
            deleteQuietly(key);
        }
    }

    private void deleteQuietly(String key) {
        try {
            fileStorage.delete(key);
        } catch (RuntimeException ex) {
            log.warn("operation=derivative_purge_delete_failed key={} error={}", key, ex.getMessage());
        }
    }
}
