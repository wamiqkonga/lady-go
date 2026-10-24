package com.ladygo.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Adversarial review finding, HIGH: {@link S3FileStorage}/{@link StorageConfig} - the only
 * {@link FileStorage} wired in staging/prod - had never been exercised by any test, mocked or real,
 * before this. {@code docker pull minio/minio} is access-denied in this environment
 * (docs/api/driver-onboarding.md section 1); {@code localstack/localstack} pulls cleanly and is used
 * here instead, exercising the exact bean-construction code {@link StorageConfig} ships.
 */
@Testcontainers
class S3FileStorageIntegrationTest {

    private static final String BUCKET = "ladygo-test-bucket";

    /** Pinned to an exact version, not a floating major tag, so CI doesn't silently pick up a new
     *  localstack release mid-pipeline (the user's explicit ask after the adversarial review). */
    @Container
    static LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
                    .withServices(LocalStackContainer.Service.S3);

    private FileStorage storage;

    @BeforeEach
    void setUp() {
        S3Client bootstrapClient = S3Client.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.S3))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .forcePathStyle(true)
                .build();
        bootstrapClient.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());

        // The exact bean StorageConfig constructs in staging/prod - not a reimplementation of it.
        storage = new StorageConfig().fileStorage(
                BUCKET,
                localstack.getRegion(),
                localstack.getEndpointOverride(LocalStackContainer.Service.S3).toString(),
                localstack.getAccessKey(),
                localstack.getSecretKey());
    }

    @Test
    void uploadThenDownloadRoundTrips() {
        storage.upload("k1", new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), 5, "text/plain");

        assertThat(storage.download("k1")).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void deleteRemovesTheObject() {
        storage.upload("k2", new ByteArrayInputStream("bye".getBytes(StandardCharsets.UTF_8)), 3, "text/plain");

        storage.delete("k2");

        assertThatThrownBy(() -> storage.download("k2")).isInstanceOf(FileStorageException.class);
    }

    @Test
    void presignedDownloadUrlActuallyServesTheBytesOverHttp() throws Exception {
        storage.upload(
                "k3", new ByteArrayInputStream("presigned-bytes".getBytes(StandardCharsets.UTF_8)), 15, "text/plain");
        URI url = storage.presignedDownloadUrl("k3", Duration.ofMinutes(5));

        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("presigned-bytes".getBytes(StandardCharsets.UTF_8));
    }

    /** docs/product/admin-web-design.md section 2 / docs/product/pilot-plan.md's security defaults -
     *  a presigned URL to a sensitive document/selfie derivative must never be cacheable, in case it
     *  ends up in a shared proxy or browser disk cache after the TTL's purpose has already been served. */
    @Test
    void presignedDownloadUrlResponseIsNeverCacheable() throws Exception {
        storage.upload("k4", new ByteArrayInputStream("sensitive".getBytes(StandardCharsets.UTF_8)), 9, "text/plain");
        URI url = storage.presignedDownloadUrl("k4", Duration.ofMinutes(5));

        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
    }

    /** S3 objects carry their own real LastModified, so - unlike the local-storage test - there's no
     *  need to fake file mtimes; a future cutoff makes a just-uploaded object count as "older", a past
     *  cutoff excludes it, which is enough to prove the filtering logic without flaky real-time waits. */
    @Test
    void listKeysOlderThanFiltersByLastModified() {
        storage.upload("driver-document-derivatives/k5",
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1, "image/png");

        assertThat(storage.listKeysOlderThan("driver-document-derivatives/", Instant.now().plusSeconds(60)))
                .contains("driver-document-derivatives/k5");
        assertThat(storage.listKeysOlderThan("driver-document-derivatives/", Instant.now().minusSeconds(60)))
                .doesNotContain("driver-document-derivatives/k5");
    }
}
