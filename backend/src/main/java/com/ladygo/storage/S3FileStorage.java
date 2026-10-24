package com.ladygo.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Prod/staging {@link FileStorage} - AWS S3 (or an S3-compatible endpoint for staging/self-hosted),
 * SSE at the bucket, short-lived presigned download URLs (ADR-009). Not {@code @Component} - wired
 * explicitly by {@link StorageConfig} so the bucket name and client are configured together.
 */
public class S3FileStorage implements FileStorage {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3FileStorage(S3Client client, S3Presigner presigner, String bucket) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public StoredFile upload(String key, InputStream content, long sizeBytes, String contentType) {
        try {
            client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                    RequestBody.fromInputStream(content, sizeBytes));
            return new StoredFile(key, sizeBytes, contentType);
        } catch (S3Exception ex) {
            throw new FileStorageException(ex);
        }
    }

    @Override
    public byte[] download(String key) {
        try (ResponseInputStream<GetObjectResponse> response =
                client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            return response.readAllBytes();
        } catch (Exception ex) {
            throw new FileStorageException(ex);
        }
    }

    @Override
    public URI presignedDownloadUrl(String key, Duration ttl) {
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(bucket).key(key).responseCacheControl("no-store").build())
                .build();
        try {
            return presigner.presignGetObject(request).url().toURI();
        } catch (Exception ex) {
            throw new FileStorageException(ex);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception ex) {
            throw new FileStorageException(ex);
        }
    }

    /** A bucket-level lifecycle rule expiring the same prefixes is the primary, infrastructure-native
     *  backstop in prod (docs/development/ci-secrets.md's sibling ops doc should record the exact
     *  rule once the bucket is provisioned); this sweep exists so the backstop doesn't depend on that
     *  having been configured correctly, and so local/dev behaves identically to prod. */
    @Override
    public List<String> listKeysOlderThan(String prefix, Instant cutoff) {
        List<String> keys = new ArrayList<>();
        try {
            String continuationToken = null;
            do {
                ListObjectsV2Request.Builder requestBuilder =
                        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);
                if (continuationToken != null) {
                    requestBuilder.continuationToken(continuationToken);
                }
                ListObjectsV2Response response = client.listObjectsV2(requestBuilder.build());
                for (S3Object object : response.contents()) {
                    if (object.lastModified().isBefore(cutoff)) {
                        keys.add(object.key());
                    }
                }
                continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
            } while (continuationToken != null);
            return keys;
        } catch (S3Exception ex) {
            throw new FileStorageException(ex);
        }
    }
}
