package com.ladygo.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {

    private static final String SECRET = "test-only-local-storage-secret-at-least-32-bytes-long";

    private LocalFileStorage newStorage(java.nio.file.Path dir) {
        return new LocalFileStorage(dir.toString(), SECRET, "http://localhost:8080");
    }

    @Test
    void uploadThenReadReturnsTheSameBytes(@TempDir java.nio.file.Path dir) {
        LocalFileStorage storage = newStorage(dir);
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);

        StoredFile stored = storage.upload("k1", new ByteArrayInputStream(bytes), bytes.length, "text/plain");

        assertThat(stored.key()).isEqualTo("k1");
        assertThat(storage.read("k1")).isEqualTo(bytes);
        assertThat(storage.contentType("k1")).isEqualTo("text/plain");
    }

    @Test
    void deleteRemovesTheFile(@TempDir java.nio.file.Path dir) {
        LocalFileStorage storage = newStorage(dir);
        storage.upload("k1", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1, "text/plain");

        storage.delete("k1");

        assertThatThrownBy(() -> storage.read("k1")).isInstanceOf(FileStorageException.class);
    }

    @Test
    void presignedUrlSignatureIsValidBeforeExpiryAndInvalidAfter(@TempDir java.nio.file.Path dir) throws Exception {
        LocalFileStorage storage = newStorage(dir);
        URI url = storage.presignedDownloadUrl("k1", Duration.ofMinutes(5));
        Map<String, String> params = queryParams(url);

        assertThat(storage.isSignatureValid(params.get("key"), Long.parseLong(params.get("exp")), params.get("sig")))
                .isTrue();

        long alreadyExpired = System.currentTimeMillis() / 1000 - 10;
        assertThat(storage.isSignatureValid(params.get("key"), alreadyExpired, params.get("sig"))).isFalse();
    }

    @Test
    void presignedUrlSignatureRejectsATamperedKey(@TempDir java.nio.file.Path dir) throws Exception {
        LocalFileStorage storage = newStorage(dir);
        URI url = storage.presignedDownloadUrl("k1", Duration.ofMinutes(5));
        Map<String, String> params = queryParams(url);

        assertThat(storage.isSignatureValid("a-different-key", Long.parseLong(params.get("exp")), params.get("sig")))
                .isFalse();
    }

    /** The backstop sweep for an orphaned throwaway derivative (docs/product/admin-web-design.md
     *  section 2's security fix) - must find a file older than the cutoff and leave a newer one alone. */
    @Test
    void listKeysOlderThanFindsOnlyFilesPastTheCutoff(@TempDir java.nio.file.Path dir) throws Exception {
        LocalFileStorage storage = newStorage(dir);
        storage.upload("driver-document-derivatives/old", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1, "image/png");
        storage.upload("driver-document-derivatives/new", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1, "image/png");
        Files.setLastModifiedTime(
                dir.resolve("driver-document-derivatives/old"), FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        List<String> old = storage.listKeysOlderThan("driver-document-derivatives/", Instant.now().minus(Duration.ofDays(1)));

        assertThat(old).containsExactly("driver-document-derivatives/old");
    }

    @Test
    void listKeysOlderThanOnAMissingPrefixReturnsEmpty(@TempDir java.nio.file.Path dir) {
        LocalFileStorage storage = newStorage(dir);

        assertThat(storage.listKeysOlderThan("nothing-here/", Instant.now())).isEmpty();
    }

    @Test
    void pathTraversalInAKeyIsRejected(@TempDir java.nio.file.Path dir) {
        LocalFileStorage storage = newStorage(dir);

        assertThatThrownBy(() -> storage.upload(
                "../../etc/passwd", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, String> queryParams(URI uri) throws Exception {
        Map<String, String> out = new java.util.HashMap<>();
        for (String pair : uri.getQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            out.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return out;
    }
}
