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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class MediaService {

    static final String MEDIA_LIST_CACHE_PREFIX = "media:list:v3:user:";

    private final MediaFileMapper mediaFileMapper;
    private final ObjectStorageService objectStorageService;
    private final ExternalVideoImportService externalVideoImportService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TranscriptService transcriptService;
    private final ChapterService chapterService;
    private final VisualEvidenceService visualEvidenceService;
    private final RedissonClient redissonClient;

    public MediaService(
            MediaFileMapper mediaFileMapper,
            ObjectStorageService objectStorageService,
            ExternalVideoImportService externalVideoImportService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            TranscriptService transcriptService,
            ChapterService chapterService,
            VisualEvidenceService visualEvidenceService,
            RedissonClient redissonClient
    ) {
        this.mediaFileMapper = mediaFileMapper;
        this.objectStorageService = objectStorageService;
        this.externalVideoImportService = externalVideoImportService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.transcriptService = transcriptService;
        this.chapterService = chapterService;
        this.visualEvidenceService = visualEvidenceService;
        this.redissonClient = redissonClient;
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

    public MediaDetailResponse getDetail(Long mediaId, Long userId) {
        MediaFile media = getOwnedMedia(mediaId, userId);
        String playbackUrl = media.getObjectKey() == null || media.getObjectKey().isBlank()
                ? null
                : objectStorageService.createReadUrl(media.getObjectKey());
        return MediaDetailResponse.from(media, playbackUrl);
    }

    public void requireOwnership(Long mediaId, Long userId) {
        getOwnedMedia(mediaId, userId);
    }

    public boolean isStudyMode(Long mediaId, Long userId) {
        return "STUDY".equals(getOwnedMedia(mediaId, userId).getAnalysisMode());
    }

    public MediaTranscriptResponse getTranscript(Long mediaId, Long userId) {
        getOwnedMedia(mediaId, userId);
        return new MediaTranscriptResponse(
                mediaId,
                transcriptService.listByMediaId(mediaId).stream()
                        .map(TranscriptSegmentResponse::from)
                        .toList()
        );
    }

    public MediaChaptersResponse getChapters(Long mediaId, Long userId) {
        getOwnedMedia(mediaId, userId);
        return new MediaChaptersResponse(
                mediaId,
                chapterService.listByMediaId(mediaId).stream()
                        .map(VideoChapterResponse::from)
                        .toList()
        );
    }

    public void delete(Long mediaId, Long userId) {
        MediaFile media = getOwnedMedia(mediaId, userId);
        if (media.getContentSha256() == null || media.getContentSha256().isBlank()) {
            deleteMedia(media, userId);
            return;
        }

        RLock lock = redissonClient.getLock(contentLockKey(userId, media.getContentSha256()));
        lock.lock();
        try {
            deleteMedia(getOwnedMedia(mediaId, userId), userId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void deleteMedia(MediaFile media, Long userId) {
        if (media.getObjectKey() != null && !media.getObjectKey().isBlank()) {
            objectStorageService.remove(media.getObjectKey());
        }
        visualEvidenceService.removeObjects(media.getId());
        mediaFileMapper.deleteById(media.getId());
        invalidateList(userId);
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
                        .eq(MediaFile::getContentSha256, contentSha256)
        );
    }

    static String contentLockKey(Long userId, String contentSha256) {
        return "lock:media-content:" + userId + ":" + contentSha256;
    }

    private MediaFile getOwnedMedia(Long mediaId, Long userId) {
        requireUserId(userId);
        MediaFile media = mediaFileMapper.selectOne(
                new LambdaQueryWrapper<MediaFile>()
                        .eq(MediaFile::getId, mediaId)
                        .eq(MediaFile::getUserId, userId)
        );
        if (media == null) {
            throw new IllegalArgumentException("Media does not exist: " + mediaId);
        }
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
