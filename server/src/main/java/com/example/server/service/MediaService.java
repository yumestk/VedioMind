package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.dto.MediaDetailResponse;
import com.example.server.dto.MediaChaptersResponse;
import com.example.server.dto.MediaListItemResponse;
import com.example.server.dto.MediaTranscriptResponse;
import com.example.server.dto.TranscriptSegmentResponse;
import com.example.server.dto.VideoChapterResponse;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.external.ExternalVideoImportService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    static final String MEDIA_LIST_CACHE_PREFIX = "media:list:v4:user:";

    private final StudyAccessService access;
    private final MediaFileMapper mediaFileMapper;
    private final ObjectStorageService objectStorageService;
    private final ExternalVideoImportService externalVideoImportService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TranscriptService transcriptService;
    private final ChapterService chapterService;
    private final VisualEvidenceService visualEvidenceService;
    private final RedissonClient redissonClient;
    private final QdrantKnowledgeIndexService qdrantIndexService;

    public MediaService(
            MediaFileMapper mediaFileMapper,
            ObjectStorageService objectStorageService,
            ExternalVideoImportService externalVideoImportService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            TranscriptService transcriptService,
            ChapterService chapterService,
            VisualEvidenceService visualEvidenceService,
            RedissonClient redissonClient,
            QdrantKnowledgeIndexService qdrantIndexService,
            StudyAccessService access
    ) {
        this.access = access;
        this.mediaFileMapper = mediaFileMapper;
        this.objectStorageService = objectStorageService;
        this.externalVideoImportService = externalVideoImportService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.transcriptService = transcriptService;
        this.chapterService = chapterService;
        this.visualEvidenceService = visualEvidenceService;
        this.redissonClient = redissonClient;
        this.qdrantIndexService = qdrantIndexService;
    }

    public MediaListItemResponse importFromUrl(String url, Long userId) {
        requireUserId(userId);
        MediaFile media = externalVideoImportService.importVideo(url, userId);
        invalidateList(userId);
        return MediaListItemResponse.from(media);
    }

    public List<MediaListItemResponse> list(Long userId) {
        requireUserId(userId);
        String cacheKey = cacheKey(userId);

        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<>() { });
            }
        } catch (Exception ignored) {
            // A cache failure must not make the media list unavailable.
        }

        List<MediaListItemResponse> result = mediaFileMapper.selectList(
                        new LambdaQueryWrapper<MediaFile>()
                                .eq(MediaFile::getUserId, userId)
                                .isNull(MediaFile::getDeletedAt)
                                .orderByDesc(MediaFile::getId)
                ).stream()
                .map(MediaListItemResponse::from)
                .toList();

        try {
            redisTemplate.opsForValue().set(
                    cacheKey,
                    objectMapper.writeValueAsString(result),
                    30,
                    TimeUnit.MINUTES
            );
        } catch (Exception ignored) {
            // The database result is still valid when Redis is unavailable.
        }
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MediaDetailResponse getDetail(Long mediaId, Long userId) {
        return getDetail(mediaId, userId, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MediaDetailResponse getDetail(Long mediaId, Long userId, Long courseId) {
        access.lockReadable(mediaId, courseId, userId);
        MediaFile media = mediaFileMapper.selectById(mediaId);
        String playbackUrl = media.getObjectKey() == null || media.getObjectKey().isBlank()
                ? null
                : objectStorageService.createReadUrl(media.getObjectKey());
        return MediaDetailResponse.from(media, playbackUrl, userId.equals(media.getUserId()));
    }

    public void requireOwnership(Long mediaId, Long userId) {
        getOwnedMedia(mediaId, userId);
    }

    public boolean isStudyMode(Long mediaId, Long userId) {
        return "STUDY".equals(getReadableMedia(mediaId, userId).getAnalysisMode());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MediaTranscriptResponse getTranscript(Long mediaId, Long userId) {
        getReadableMedia(mediaId, userId);
        return new MediaTranscriptResponse(
                mediaId,
                transcriptService.listByMediaId(mediaId).stream()
                        .map(TranscriptSegmentResponse::from)
                        .toList()
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MediaChaptersResponse getChapters(Long mediaId, Long userId) {
        getReadableMedia(mediaId, userId);
        return new MediaChaptersResponse(
                mediaId,
                chapterService.listByMediaId(mediaId).stream()
                        .map(VideoChapterResponse::from)
                        .toList()
        );
    }

    public void delete(Long mediaId, Long userId) {
        MediaFile media = getDeletableMedia(mediaId, userId);
        if (media.getContentSha256() == null || media.getContentSha256().isBlank()) {
            deleteMedia(media, userId);
            return;
        }

        RLock lock = redissonClient.getLock(contentLockKey(userId, media.getContentSha256()));
        lock.lock();
        try {
            deleteMedia(getDeletableMedia(mediaId, userId), userId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void deleteMedia(MediaFile media, Long userId) {
        access.deleteSource(media.getId(), userId);
        invalidateList(userId);
        try {
            if (media.getObjectKey() != null && !media.getObjectKey().isBlank()) {
                objectStorageService.remove(media.getObjectKey());
            }
        } catch (RuntimeException error) {
            log.warn("Deleted source {}: video cleanup requires a repeated DELETE", media.getId());
        }
        try {
            visualEvidenceService.removeObjects(media.getId());
        } catch (RuntimeException error) {
            log.warn("Deleted source {}: visual cleanup requires a repeated DELETE", media.getId());
        }
        try {
            qdrantIndexService.deleteMedia(media.getId());
        } catch (RuntimeException error) {
            log.warn("Deleted source {}: index cleanup requires a repeated DELETE", media.getId());
        }
    }

    MediaListItemResponse registerLocalUpload(
            Long userId,
            String filename,
            String objectKey,
            String mimeType,
            long fileSize,
            String contentSha256
    ) {
        requireUserId(userId);
        MediaFile existing = mediaFileMapper.selectOne(
                new LambdaQueryWrapper<MediaFile>().eq(MediaFile::getObjectKey, objectKey)
        );
        if (existing != null) {
            if (existing.getDeletedAt() != null) throw new IllegalArgumentException("Upload source has been deleted");
            if (!userId.equals(existing.getUserId())) {
                throw new IllegalStateException("Media object is owned by another user");
            }
            return MediaListItemResponse.from(existing);
        }

        MediaFile media = new MediaFile();
        media.setUserId(userId);
        media.setFilename(filename == null || filename.isBlank() ? objectKey : filename);
        media.setStatus("COMPLETED");
        media.setSourceType("LOCAL_UPLOAD");
        media.setAnalysisMode("STUDY");
        media.setObjectKey(objectKey);
        media.setMimeType(mimeType);
        media.setFileSize(fileSize);
        media.setContentSha256(contentSha256);
        media.setUploadTime(LocalDateTime.now());
        mediaFileMapper.insert(media);
        invalidateList(userId);
        return MediaListItemResponse.from(media);
    }

    MediaListItemResponse getListItem(Long mediaId, Long userId) {
        return MediaListItemResponse.from(getOwnedMedia(mediaId, userId));
    }

    MediaFile findByContentSha256(Long userId, String contentSha256) {
        requireUserId(userId);
        if (contentSha256 == null || contentSha256.isBlank()) {
            return null;
        }
        return mediaFileMapper.selectOne(
                new LambdaQueryWrapper<MediaFile>()
                        .eq(MediaFile::getUserId, userId)
                        .isNull(MediaFile::getDeletedAt)
                        .eq(MediaFile::getContentSha256, contentSha256)
        );
    }

    static String contentLockKey(Long userId, String contentSha256) {
        return "lock:media-content:" + userId + ":" + contentSha256;
    }

    public void requireReadable(Long mediaId, Long courseId, Long userId) {
        access.requireRead(mediaId, courseId, userId);
    }

    public boolean canRead(Long mediaId, Long courseId, Long userId) {
        return access.canRead(mediaId, courseId, userId);
    }

    private MediaFile getReadableMedia(Long mediaId, Long userId) {
        access.lockReadable(mediaId, null, userId);
        return mediaFileMapper.selectById(mediaId);
    }

    private MediaFile getOwnedMedia(Long mediaId, Long userId) {
        requireUserId(userId);
        MediaFile media = mediaFileMapper.selectOne(
                new LambdaQueryWrapper<MediaFile>()
                        .eq(MediaFile::getId, mediaId)
                        .eq(MediaFile::getUserId, userId)
                        .isNull(MediaFile::getDeletedAt)
        );
        if (media == null) {
            throw new IllegalArgumentException("Media does not exist: " + mediaId);
        }
        return media;
    }

    private MediaFile getDeletableMedia(Long mediaId, Long userId) {
        requireUserId(userId);
        MediaFile media = mediaFileMapper.selectOne(new LambdaQueryWrapper<MediaFile>()
                .eq(MediaFile::getId, mediaId).eq(MediaFile::getUserId, userId));
        if (media == null) throw new IllegalArgumentException("Media does not exist: " + mediaId);
        return media;
    }

    private void requireUserId(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
    }

    private void invalidateList(Long userId) {
        try {
            redisTemplate.delete(cacheKey(userId));
        } catch (Exception ignored) {
            // Cache invalidation is best effort; the entry expires after 30 minutes.
        }
    }

    private String cacheKey(Long userId) {
        return MEDIA_LIST_CACHE_PREFIX + userId;
    }

}
