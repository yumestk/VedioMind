package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.dto.MediaDetailResponse;
import com.example.server.dto.MediaListItemResponse;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.YtDlpUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class MediaService {

    static final String MEDIA_LIST_CACHE_PREFIX = "media:list:v2:user:";

    private final MediaFileMapper mediaFileMapper;
    private final MinioUtils minioUtils;
    private final YtDlpUtils ytDlpUtils;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public MediaService(
            MediaFileMapper mediaFileMapper,
            MinioUtils minioUtils,
            YtDlpUtils ytDlpUtils,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.mediaFileMapper = mediaFileMapper;
        this.minioUtils = minioUtils;
        this.ytDlpUtils = ytDlpUtils;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public MediaListItemResponse upload(MultipartFile file, Long userId) {
        requireUserId(userId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Upload file must not be empty");
        }

        String objectKey = null;
        try {
            objectKey = minioUtils.upload(file);
            MediaFile media = createMedia(
                    userId,
                    file.getOriginalFilename(),
                    objectKey,
                    minioUtils.resolveContentType(file.getContentType(), file.getOriginalFilename()),
                    file.getSize()
            );
            mediaFileMapper.insert(media);
            invalidateList(userId);
            return MediaListItemResponse.from(media);
        } catch (Exception exception) {
            removeCompensatingObject(objectKey, exception);
            throw new IllegalStateException("Failed to upload media", exception);
        }
    }

    public MediaListItemResponse importFromUrl(String url, Long userId) {
        requireUserId(userId);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Video URL must not be empty");
        }

        File temporaryFile = null;
        String objectKey = null;
        try {
            temporaryFile = ytDlpUtils.downloadVideo(url);
            String contentType = minioUtils.resolveContentType("video/mp4", temporaryFile.getName());
            objectKey = minioUtils.upload(temporaryFile, contentType);

            MediaFile media = createMedia(
                    userId,
                    "WEB_" + temporaryFile.getName(),
                    objectKey,
                    contentType,
                    temporaryFile.length()
            );
            mediaFileMapper.insert(media);
            invalidateList(userId);
            return MediaListItemResponse.from(media);
        } catch (Exception exception) {
            removeCompensatingObject(objectKey, exception);
            throw new IllegalStateException("Failed to import media from URL", exception);
        } finally {
            if (temporaryFile != null && temporaryFile.exists()) {
                temporaryFile.delete();
            }
        }
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
        return MediaDetailResponse.from(media, minioUtils.createReadUrl(media.getObjectKey()));
    }

    public void delete(Long mediaId, Long userId) {
        MediaFile media = getOwnedMedia(mediaId, userId);
        minioUtils.remove(media.getObjectKey());
        mediaFileMapper.deleteById(mediaId);
        invalidateList(userId);
    }

    private MediaFile createMedia(
            Long userId,
            String filename,
            String objectKey,
            String mimeType,
            long fileSize
    ) {
        MediaFile media = new MediaFile();
        media.setUserId(userId);
        media.setFilename(filename == null || filename.isBlank() ? objectKey : filename);
        media.setStatus("COMPLETED");
        media.setObjectKey(objectKey);
        media.setMimeType(mimeType);
        media.setFileSize(fileSize);
        media.setUploadTime(LocalDateTime.now());
        return media;
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

    private void removeCompensatingObject(String objectKey, Exception originalException) {
        if (objectKey == null) {
            return;
        }
        try {
            minioUtils.remove(objectKey);
        } catch (Exception compensationException) {
            originalException.addSuppressed(compensationException);
        }
    }
}
