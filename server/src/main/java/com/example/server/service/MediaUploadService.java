package com.example.server.service;

import com.example.server.dto.CompleteMediaUploadRequest;
import com.example.server.dto.CompleteUploadPartRequest;
import com.example.server.dto.InitiateMediaUploadRequest;
import com.example.server.dto.InitiateMediaUploadResponse;
import com.example.server.dto.MediaListItemResponse;
import com.example.server.dto.MediaUploadSessionResponse;
import com.example.server.dto.UploadPartUrlResponse;
import com.example.server.dto.UploadedPartResponse;
import com.example.server.entity.MediaUploadSession;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaUploadSessionMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MediaUploadService {

    static final long PART_SIZE = 32L * 1024 * 1024;
    private static final String UPLOADING = "UPLOADING";
    private static final String COMPLETED = "COMPLETED";
    private static final String ABORTED = "ABORTED";

    private final MediaUploadSessionMapper sessionMapper;
    private final ObjectStorageService objectStorageService;
    private final MediaService mediaService;
    private final RedissonClient redissonClient;
    private final long maxFileSize;

    public MediaUploadService(
            MediaUploadSessionMapper sessionMapper,
            ObjectStorageService objectStorageService,
            MediaService mediaService,
            RedissonClient redissonClient,
            @Value("${media.upload.max-file-size-bytes}") long maxFileSize
    ) {
        this.sessionMapper = sessionMapper;
        this.objectStorageService = objectStorageService;
        this.mediaService = mediaService;
        this.redissonClient = redissonClient;
        this.maxFileSize = maxFileSize;
    }

    public InitiateMediaUploadResponse initiate(InitiateMediaUploadRequest request, Long userId) {
        requireUserId(userId);
        validateRequest(request);

        String filename = request.filename().trim();
        String mimeType = objectStorageService.resolveContentType(request.mimeType(), filename);
        if (!mimeType.startsWith("video/")) {
            throw new IllegalArgumentException("Only video files can be uploaded");
        }

        String objectKey = objectStorageService.createObjectKey(filename);
        String uploadId = objectStorageService.createMultipartUpload(objectKey, mimeType);
        MediaUploadSession session = new MediaUploadSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setUploadId(uploadId);
        session.setObjectKey(objectKey);
        session.setFilename(filename);
        session.setMimeType(mimeType);
        session.setFileSize(request.fileSize());
        session.setPartSize(PART_SIZE);
        session.setStatus(UPLOADING);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(session.getCreatedAt());
        try {
            sessionMapper.insert(session);
        } catch (RuntimeException exception) {
            try {
                objectStorageService.abortMultipartUpload(objectKey, uploadId);
            } catch (RuntimeException compensationException) {
                exception.addSuppressed(compensationException);
            }
            throw exception;
        }
        return new InitiateMediaUploadResponse(session.getId(), PART_SIZE);
    }

    public MediaUploadSessionResponse get(String sessionId, Long userId) {
        MediaUploadSession session = getOwnedSession(sessionId, userId);
        List<UploadedPartResponse> parts = UPLOADING.equals(session.getStatus())
                ? objectStorageService.listParts(session.getObjectKey(), session.getUploadId()).stream()
                        .map(part -> new UploadedPartResponse(part.partNumber(), part.etag(), part.size()))
                        .toList()
                : List.of();
        return toResponse(session, parts);
    }

    public UploadPartUrlResponse createPartUrl(String sessionId, int partNumber, Long userId) {
        MediaUploadSession session = getOwnedSession(sessionId, userId);
        requireUploading(session);
        int totalParts = totalParts(session);
        if (partNumber < 1 || partNumber > totalParts) {
            throw new IllegalArgumentException("Invalid upload part number: " + partNumber);
        }
        return new UploadPartUrlResponse(
                partNumber,
                objectStorageService.createUploadPartUrl(session.getObjectKey(), session.getUploadId(), partNumber)
        );
    }

    public MediaListItemResponse complete(
            String sessionId,
            CompleteMediaUploadRequest request,
            Long userId
    ) {
        RLock lock = redissonClient.getLock("lock:media-upload:" + sessionId);
        lock.lock();
        try {
            MediaUploadSession session = getOwnedSession(sessionId, userId);
            if (COMPLETED.equals(session.getStatus())) {
                return mediaService.getListItem(session.getMediaId(), userId);
            }
            requireUploading(session);

            if (hasContentHash(session)) {
                MediaListItemResponse existing = reuseExistingMedia(session, userId);
                if (existing != null) {
                    return existing;
                }
            }

            Long finalSize = objectStorageService.objectSize(session.getObjectKey());
            if (finalSize == null) {
                List<ObjectStorageService.StoredPart> storedParts = validateParts(session, request);
                objectStorageService.completeMultipartUpload(
                        session.getObjectKey(), session.getUploadId(), storedParts
                );
                finalSize = objectStorageService.objectSize(session.getObjectKey());
            }
            if (!session.getFileSize().equals(finalSize)) {
                throw new IllegalStateException("Completed upload size does not match the selected file");
            }

            if (!hasContentHash(session)) {
                session.setContentSha256(objectStorageService.calculateSha256(session.getObjectKey()));
                session.setUpdatedAt(LocalDateTime.now());
                sessionMapper.updateById(session);
            }

            return registerOrReuseMedia(session, userId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private MediaListItemResponse reuseExistingMedia(MediaUploadSession session, Long userId) {
        RLock lock = redissonClient.getLock(MediaService.contentLockKey(userId, session.getContentSha256()));
        lock.lock();
        try {
            MediaFile existing = mediaService.findByContentSha256(userId, session.getContentSha256());
            return existing == null ? null : reuseMedia(session, existing);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private MediaListItemResponse registerOrReuseMedia(MediaUploadSession session, Long userId) {
        RLock lock = redissonClient.getLock(MediaService.contentLockKey(userId, session.getContentSha256()));
        lock.lock();
        try {
            MediaFile existing = mediaService.findByContentSha256(userId, session.getContentSha256());
            if (existing != null) {
                return reuseMedia(session, existing);
            }

            try {
                MediaListItemResponse media = mediaService.registerLocalUpload(
                        userId,
                        session.getFilename(),
                        session.getObjectKey(),
                        session.getMimeType(),
                        session.getFileSize(),
                        session.getContentSha256()
                );
                completeSession(session, media.id());
                return media;
            } catch (DuplicateKeyException exception) {
                MediaFile duplicate = mediaService.findByContentSha256(userId, session.getContentSha256());
                if (duplicate == null) {
                    throw exception;
                }
                return reuseMedia(session, duplicate);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private MediaListItemResponse reuseMedia(MediaUploadSession session, MediaFile existing) {
        if (!session.getObjectKey().equals(existing.getObjectKey())) {
            objectStorageService.remove(session.getObjectKey());
        }
        MediaListItemResponse media = MediaListItemResponse.from(existing);
        completeSession(session, media.id());
        return media;
    }

    private void completeSession(MediaUploadSession session, Long mediaId) {
        session.setStatus(COMPLETED);
        session.setMediaId(mediaId);
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    private boolean hasContentHash(MediaUploadSession session) {
        return session.getContentSha256() != null && !session.getContentSha256().isBlank();
    }

    public void abort(String sessionId, Long userId) {
        MediaUploadSession session = getOwnedSession(sessionId, userId);
        if (ABORTED.equals(session.getStatus())) {
            return;
        }
        if (COMPLETED.equals(session.getStatus())) {
            throw new IllegalStateException("A completed upload cannot be aborted");
        }
        try {
            objectStorageService.abortMultipartUpload(session.getObjectKey(), session.getUploadId());
        } catch (S3Exception exception) {
            if (exception.awsErrorDetails() == null
                    || !"NoSuchUpload".equals(exception.awsErrorDetails().errorCode())) {
                throw exception;
            }
        }
        session.setStatus(ABORTED);
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    private List<ObjectStorageService.StoredPart> validateParts(
            MediaUploadSession session,
            CompleteMediaUploadRequest request
    ) {
        if (request == null || request.parts() == null) {
            throw new IllegalArgumentException("Completed upload parts are required");
        }
        Map<Integer, CompleteUploadPartRequest> requestedParts;
        try {
            requestedParts = request.parts().stream().collect(Collectors.toMap(
                    CompleteUploadPartRequest::partNumber,
                    Function.identity()
            ));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Upload parts must contain unique part numbers", exception);
        }

        int totalParts = totalParts(session);
        if (requestedParts.size() != totalParts) {
            throw new IllegalArgumentException("Upload is incomplete");
        }

        List<ObjectStorageService.StoredPart> storedParts = objectStorageService
                .listParts(session.getObjectKey(), session.getUploadId())
                .stream()
                .sorted(Comparator.comparingInt(ObjectStorageService.StoredPart::partNumber))
                .toList();
        if (storedParts.size() != totalParts) {
            throw new IllegalArgumentException("Upload is incomplete");
        }

        long uploadedSize = 0;
        for (int index = 0; index < storedParts.size(); index++) {
            int expectedPartNumber = index + 1;
            ObjectStorageService.StoredPart stored = storedParts.get(index);
            CompleteUploadPartRequest requested = requestedParts.get(expectedPartNumber);
            long expectedSize = expectedPartSize(session, expectedPartNumber);
            if (stored.partNumber() != expectedPartNumber
                    || stored.size() != expectedSize
                    || requested == null
                    || !normalizeEtag(stored.etag()).equals(normalizeEtag(requested.etag()))) {
                throw new IllegalArgumentException("Upload part verification failed: " + expectedPartNumber);
            }
            uploadedSize += stored.size();
        }
        if (uploadedSize != session.getFileSize()) {
            throw new IllegalArgumentException("Uploaded size does not match the selected file");
        }
        return storedParts;
    }

    private long expectedPartSize(MediaUploadSession session, int partNumber) {
        if (partNumber < totalParts(session)) {
            return session.getPartSize();
        }
        return session.getFileSize() - (long) (partNumber - 1) * session.getPartSize();
    }

    private int totalParts(MediaUploadSession session) {
        return Math.toIntExact((session.getFileSize() + session.getPartSize() - 1) / session.getPartSize());
    }

    private MediaUploadSessionResponse toResponse(
            MediaUploadSession session,
            List<UploadedPartResponse> parts
    ) {
        return new MediaUploadSessionResponse(
                session.getId(),
                session.getStatus(),
                session.getFilename(),
                session.getFileSize(),
                session.getPartSize(),
                session.getMediaId(),
                parts
        );
    }

    private MediaUploadSession getOwnedSession(String sessionId, Long userId) {
        requireUserId(userId);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Upload session id is required");
        }
        MediaUploadSession session = sessionMapper.selectById(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new IllegalArgumentException("Upload session does not exist");
        }
        return session;
    }

    private void validateRequest(InitiateMediaUploadRequest request) {
        if (request == null || request.filename() == null || request.filename().isBlank()) {
            throw new IllegalArgumentException("Filename is required");
        }
        if (request.filename().length() > 500) {
            throw new IllegalArgumentException("Filename is too long");
        }
        if (request.fileSize() == null || request.fileSize() <= 0) {
            throw new IllegalArgumentException("File must not be empty");
        }
        if (request.fileSize() > maxFileSize) {
            throw new IllegalArgumentException("Video exceeds the configured upload size limit");
        }
    }

    private void requireUploading(MediaUploadSession session) {
        if (!UPLOADING.equals(session.getStatus())) {
            throw new IllegalStateException("Upload session is not active");
        }
    }

    private void requireUserId(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
    }

    private String normalizeEtag(String etag) {
        if (etag == null) {
            return "";
        }
        String normalized = etag.trim();
        return normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")
                ? normalized.substring(1, normalized.length() - 1)
                : normalized;
    }
}
