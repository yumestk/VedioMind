package com.example.server.utils;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class MinioUtils {

    private static final int PLAYBACK_URL_EXPIRY_HOURS = 2;

    private final MinioClient minioClient;
    private final String bucketName;

    public MinioUtils(
            MinioClient minioClient,
            @Value("${minio.bucketName}") String bucketName
    ) {
        this.minioClient = minioClient;
        this.bucketName = bucketName;
    }

    public String upload(MultipartFile file) throws Exception {
        String objectKey = createObjectKey(file.getOriginalFilename());
        String contentType = resolveContentType(file.getContentType(), file.getOriginalFilename());

        try (InputStream inputStream = file.getInputStream()) {
            putObject(objectKey, inputStream, file.getSize(), contentType);
        }
        return objectKey;
    }

    public String upload(File file, String contentType) throws Exception {
        String objectKey = createObjectKey(file.getName());
        String resolvedContentType = resolveContentType(contentType, file.getName());

        try (InputStream inputStream = new FileInputStream(file)) {
            putObject(objectKey, inputStream, file.length(), resolvedContentType);
        }
        return objectKey;
    }

    public String createReadUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("Media object key is missing");
        }
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(objectKey)
                            .expiry(PLAYBACK_URL_EXPIRY_HOURS, TimeUnit.HOURS)
                            .build()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create a media read URL", exception);
        }
    }

    public void remove(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to remove media object: " + objectKey, exception);
        }
    }

    public String resolveContentType(String providedContentType, String filename) {
        if (providedContentType != null
                && !providedContentType.isBlank()
                && !MediaType.APPLICATION_OCTET_STREAM_VALUE.equalsIgnoreCase(providedContentType)) {
            return providedContentType;
        }
        return MediaTypeFactory.getMediaType(filename == null ? "" : filename)
                .map(MediaType::toString)
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    private void putObject(String objectKey, InputStream inputStream, long size, String contentType) throws Exception {
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectKey)
                        .stream(inputStream, size, -1)
                        .contentType(contentType)
                        .build()
        );
    }

    private String createObjectKey(String filename) {
        String suffix = "";
        if (filename != null && filename.contains(".")) {
            suffix = filename.substring(filename.lastIndexOf('.')).toLowerCase();
        }
        return UUID.randomUUID() + suffix;
    }
}
