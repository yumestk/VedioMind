package com.example.server.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ObjectStorageService {

    private static final Duration PLAYBACK_URL_EXPIRY = Duration.ofHours(2);
    private static final Duration UPLOAD_URL_EXPIRY = Duration.ofMinutes(15);

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucketName;

    public ObjectStorageService(
            S3Client s3Client,
            S3Presigner presigner,
            @Value("${minio.bucketName}") String bucketName
    ) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.bucketName = bucketName;
    }

    public String createMultipartUpload(String objectKey, String contentType) {
        return s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                        .bucket(bucketName)
                        .key(objectKey)
                        .contentType(contentType)
                        .build())
                .uploadId();
    }

    public String createUploadPartUrl(String objectKey, String uploadId, int partNumber) {
        UploadPartRequest request = UploadPartRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .uploadId(uploadId)
                .partNumber(partNumber)
                .build();
        return presigner.presignUploadPart(UploadPartPresignRequest.builder()
                        .signatureDuration(UPLOAD_URL_EXPIRY)
                        .uploadPartRequest(request)
                        .build())
                .url()
                .toString();
    }

    public List<StoredPart> listParts(String objectKey, String uploadId) {
        List<StoredPart> result = new ArrayList<>();
        Integer marker = null;
        do {
            var response = s3Client.listParts(ListPartsRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .uploadId(uploadId)
                    .partNumberMarker(marker)
                    .build());
            response.parts().forEach(part -> result.add(new StoredPart(
                    part.partNumber(), part.eTag(), part.size()
            )));
            marker = response.isTruncated() ? response.nextPartNumberMarker() : null;
        } while (marker != null);
        return result;
    }

    public void completeMultipartUpload(String objectKey, String uploadId, List<StoredPart> parts) {
        List<CompletedPart> completedParts = parts.stream()
                .map(part -> CompletedPart.builder()
                        .partNumber(part.partNumber())
                        .eTag(part.etag())
                        .build())
                .toList();
        s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build());
    }

    public void abortMultipartUpload(String objectKey, String uploadId) {
        s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .uploadId(uploadId)
                .build());
    }

    public Long objectSize(String objectKey) {
        try {
            return s3Client.headObject(HeadObjectRequest.builder()
                            .bucket(bucketName)
                            .key(objectKey)
                            .build())
                    .contentLength();
        } catch (NoSuchKeyException exception) {
            return null;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return null;
            }
            throw exception;
        }
    }

    public String calculateSha256(String objectKey) {
        try (InputStream input = s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build())) {
            return sha256(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read uploaded media for content hashing", exception);
        }
    }

    static String sha256(InputStream input) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }

        byte[] buffer = new byte[1024 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public String createReadUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("Media object key is missing");
        }
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(PLAYBACK_URL_EXPIRY)
                        .getObjectRequest(request -> request.bucket(bucketName).key(objectKey))
                        .build())
                .url()
                .toString();
    }

    public void remove(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build());
    }

    public void upload(Path file, String objectKey, String contentType) {
        if (file == null || objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("File and object key are required");
        }
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(objectKey)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromFile(file));
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

    public String createObjectKey(String filename) {
        String suffix = "";
        if (filename != null && filename.contains(".")) {
            suffix = filename.substring(filename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
            if (!suffix.matches("\\.[a-z0-9]{1,10}")) {
                suffix = "";
            }
        }
        return "media/" + UUID.randomUUID() + suffix;
    }

    public record StoredPart(int partNumber, String etag, long size) { }
}
