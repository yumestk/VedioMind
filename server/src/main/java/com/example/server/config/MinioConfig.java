package com.example.server.config;

import io.minio.BucketExistsArgs;
import io.minio.DeleteBucketPolicyArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.accessKey}")
    private String accessKey;

    @Value("${minio.secretKey}")
    private String secretKey;

    @Value("${minio.bucketName}")
    private String bucketName;

    @Bean
    public MinioClient minioClient() {
        try {
            MinioClient client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();

            boolean found = client.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }

            try {
                client.deleteBucketPolicy(
                        DeleteBucketPolicyArgs.builder()
                                .bucket(bucketName)
                                .build()
                );
            } catch (ErrorResponseException exception) {
                if (!"NoSuchBucketPolicy".equals(exception.errorResponse().code())) {
                    throw exception;
                }
            }

            return client;

        } catch (Exception e) {
            throw new RuntimeException("MinIO 初始化失败", e);
        }
    }
}
