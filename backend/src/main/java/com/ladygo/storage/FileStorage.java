package com.ladygo.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Provider-agnostic object storage SPI (ADR-009, docs/api/driver-onboarding.md section 1). Keys are
 * opaque, server-generated UUIDs - callers never derive a key from a subject's own ID, so guessing a
 * real driver/customer ID never lets anyone guess their file key.
 */
public interface FileStorage {

    StoredFile upload(String key, InputStream content, long sizeBytes, String contentType);

    /** Reads the raw bytes server-side - used only where the server itself needs the content (e.g. to
     *  generate a watermarked derivative), never exposed directly to a client. */
    byte[] download(String key);

    /** Short-lived, download-only. Never a public/permanent link. */
    URI presignedDownloadUrl(String key, Duration ttl);

    void delete(String key);

    /** Backstop sweep for throwaway derivatives (watermarked document/selfie copies) that should have
     *  been deleted shortly after their own presigned URL expired but weren't - e.g. the app restarted
     *  before an in-process scheduled delete fired. Returns every key under {@code prefix} whose last
     *  modification is before {@code cutoff}, so a caller can delete each one. */
    List<String> listKeysOlderThan(String prefix, Instant cutoff);
}
