package com.ladygo.storage;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Staging/prod wiring for {@link S3FileStorage} (ADR-009, docs/api/driver-onboarding.md section 1).
 * {@code ladygo.storage.s3.endpoint} is optional - unset means real AWS S3 (prod); set it to a
 * self-hosted S3-compatible endpoint for staging, exercising the exact same code path as prod.
 */
@Configuration
@Profile({"staging", "prod"})
public class StorageConfig {

    @Bean
    FileStorage fileStorage(
            @Value("${ladygo.storage.s3.bucket}") String bucket,
            @Value("${ladygo.storage.s3.region}") String region,
            @Value("${ladygo.storage.s3.endpoint:}") String endpoint,
            @Value("${ladygo.storage.s3.access-key:}") String accessKey,
            @Value("${ladygo.storage.s3.secret-key:}") String secretKey) {
        Region awsRegion = Region.of(region);
        AwsCredentialsProvider credentials = (accessKey.isBlank() || secretKey.isBlank())
                ? DefaultCredentialsProvider.create()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));

        S3ClientBuilder clientBuilder = S3Client.builder().region(awsRegion).credentialsProvider(credentials);
        S3Presigner.Builder presignerBuilder =
                S3Presigner.builder().region(awsRegion).credentialsProvider(credentials);
        if (!endpoint.isBlank()) {
            clientBuilder.endpointOverride(URI.create(endpoint));
            presignerBuilder.endpointOverride(URI.create(endpoint));
        }

        return new S3FileStorage(clientBuilder.build(), presignerBuilder.build(), bucket);
    }
}
