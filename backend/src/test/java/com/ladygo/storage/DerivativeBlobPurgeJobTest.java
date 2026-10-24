package com.ladygo.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.testsupport.IntegrationTest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * Backstop sweep for a derivative that the in-process scheduled delete never cleaned up (app restart
 * between creation and the scheduled time, docs/product/admin-web-design.md section 2's security
 * fix) - exercised against the real wiring (real {@link FileStorage}, real
 * {@code ConfigurationService} reading the seeded {@code storage.derivative.max_age.hours} default).
 */
@IntegrationTest
class DerivativeBlobPurgeJobTest {

    @Autowired
    private DerivativeBlobPurgeJob job;
    @Autowired
    private FileStorage fileStorage;
    @Value("${ladygo.storage.local.directory}")
    private String localStorageDirectory;

    private void upload(String key) {
        fileStorage.upload(key, new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1, "image/png");
    }

    private void backdate(String key, Duration age) throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of(localStorageDirectory, key);
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(age)));
    }

    @Test
    void anOrphanedDerivativeOlderThanTheConfiguredMaxAgeIsPurged() throws Exception {
        String key = "driver-document-derivatives/" + java.util.UUID.randomUUID();
        upload(key);
        backdate(key, Duration.ofHours(25)); // default bound is 24 hours (V25 seed)

        job.sweep();

        assertThatThrownBy(() -> fileStorage.download(key)).isInstanceOf(FileStorageException.class);
    }

    @Test
    void aRecentDerivativeIsLeftAlone() throws Exception {
        String key = "driver-document-derivatives/" + java.util.UUID.randomUUID();
        upload(key);
        backdate(key, Duration.ofHours(1));

        job.sweep();

        assertThat(fileStorage.download(key)).isNotEmpty();
    }
}
