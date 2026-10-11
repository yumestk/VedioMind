package com.example.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class ObjectStorageConfig {

    private static final Region SIGNING_REGION = Region.US_EAST_1;

    @Bean(destroyMethod = "close")
    public S3Client s3Client(
            @Value("${minio.endpoint}") String endpoint,
            @Value("${minio.accessKey}") String accessKey,
            @Value("${minio.secretKey}") String secretKey,
            @Value("${minio.bucketName}") String bucketName
    ) {
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(credentials(accessKey, secretKey))
                .region(SIGNING_REGION)
                .serviceConfiguration(s3Configuration())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        ensureBucket(client, bucketName);
        return client;
    }

    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(
            @Value("${minio.publicEndpoint:${minio.endpoint}}") String publicEndpoint,
            @Value("${minio.accessKey}") String accessKey,
            @Value("${minio.secretKey}") String secretKey
    ) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(publicEndpoint))
                .credentialsProvider(credentials(accessKey, secretKey))
                .region(SIGNING_REGION)
                .serviceConfiguration(s3Configuration())
                .build();
    }

    private StaticCredentialsProvider credentials(String accessKey, String secretKey) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    private S3Configuration s3Configuration() {
        return S3Configuration.builder().pathStyleAccessEnabled(true).build();
    }

    private void ensureBucket(S3Client client, String bucketName) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) {
                throw exception;
            }
            client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
        }
    }

}
