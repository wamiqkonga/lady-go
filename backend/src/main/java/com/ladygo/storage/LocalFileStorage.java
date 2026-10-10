package com.ladygo.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/test-only {@link FileStorage} - local filesystem, "presigned URL" is a short-lived HMAC-signed
 * path served by {@link LocalFileStorageController} (docs/api/driver-onboarding.md section 1), not a
 * real cloud mechanism. Same profile-gating pattern as {@code MockSmsService}/{@code MockMapsService}.
 */
@Component
@Profile({"dev", "test"})
public class LocalFileStorage implements FileStorage {

    private final Path root;
    private final LocalSignedUrlCodec codec;
    private final String baseUrl;

    public LocalFileStorage(
            @Value("${ladygo.storage.local.directory}") String directory,
            @Value("${ladygo.storage.local.secret}") String secret,
            @Value("${ladygo.storage.local.base-url}") String baseUrl) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.codec = new LocalSignedUrlCodec(secret);
        this.baseUrl = baseUrl;
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new FileStorageException(ex);
        }
    }

    @Override
    public StoredFile upload(String key, InputStream content, long sizeBytes, String contentType) {
        try {
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            Files.copy(content, target);
            Files.writeString(target.resolveSibling(target.getFileName() + ".contenttype"), contentType);
            return new StoredFile(key, sizeBytes, contentType);
        } catch (IOException ex) {
            throw new FileStorageException(ex);
        }
    }

    @Override
    public URI presignedDownloadUrl(String key, Duration ttl) {
        long expiry = Instant.now().plus(ttl).getEpochSecond();
        String signature = codec.sign(key, expiry);
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        return URI.create(baseUrl + "/internal/files?key=" + encodedKey + "&exp=" + expiry + "&sig=" + signature);
    }

    @Override
    public void delete(String key) {
        try {
            Path target = resolve(key);
            Files.deleteIfExists(target);
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".contenttype"));
        } catch (IOException ex) {
            throw new FileStorageException(ex);
        }
    }

    @Override
    public byte[] download(String key) {
        return read(key);
    }

    @Override
    public List<String> listKeysOlderThan(String prefix, Instant cutoff) {
        Path dir = resolve(prefix);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().endsWith(".contenttype"))
                    .filter(path -> isOlderThan(path, cutoff))
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .toList();
        } catch (IOException ex) {
            throw new FileStorageException(ex);
        }
    }

    private boolean isOlderThan(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (IOException ex) {
            return false;
        }
    }

    boolean isSignatureValid(String key, long expiryEpochSeconds, String signature) {
        return codec.isValid(key, expiryEpochSeconds, signature);
    }

    byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException ex) {
            throw new FileStorageException(ex);
        }
    }

    String contentType(String key) {
        try {
            Path sidecar = resolve(key).resolveSibling(resolve(key).getFileName() + ".contenttype");
            return Files.readString(sidecar);
        } catch (IOException ex) {
            return "application/octet-stream";
        }
    }

    private Path resolve(String key) {
        // Reject any key that could escape `root` via ".." traversal before it ever touches the filesystem.
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return target;
    }
}
