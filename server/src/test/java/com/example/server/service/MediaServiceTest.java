package com.example.server.service;

import com.example.server.dto.MediaDetailResponse;
import com.example.server.dto.MediaListItemResponse;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.external.ExternalVideoImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaServiceTest {

    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
    private final ExternalVideoImportService externalVideoImportService = mock(ExternalVideoImportService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final TranscriptService transcriptService = mock(TranscriptService.class);
    private final ChapterService chapterService = mock(ChapterService.class);
    private final VisualEvidenceService visualEvidenceService = mock(VisualEvidenceService.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final MediaService mediaService = new MediaService(
            mediaFileMapper,
            objectStorageService,
            externalVideoImportService,
            redisTemplate,
            new ObjectMapper(),
            transcriptService,
            chapterService,
            visualEvidenceService,
            redissonClient
    );

    @Test
    void completedMultipartUploadRegistersAStudyMedia() {
        doAnswer(invocation -> {
            MediaFile media = invocation.getArgument(0);
            media.setId(42L);
            return 1;
        }).when(mediaFileMapper).insert(any(MediaFile.class));

        MediaListItemResponse response = mediaService.registerLocalUpload(
                7L, "lesson.mp4", "objects/lesson.mp4", "video/mp4", 1024L, "sha256"
        );

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.mimeType()).isEqualTo("video/mp4");
        assertThat(response.analysisMode()).isEqualTo("STUDY");
        assertThat(response.id()).isEqualTo(42L);
        verify(mediaFileMapper).insert(any(MediaFile.class));
    }

    @Test
    void detailUsesAShortLivedPlaybackUrl() {
        MediaFile media = new MediaFile();
        media.setId(42L);
        media.setUserId(7L);
        media.setFilename("lesson.mp4");
        media.setObjectKey("objects/lesson.mp4");
        when(mediaFileMapper.selectOne(any())).thenReturn(media);
        when(objectStorageService.createReadUrl("objects/lesson.mp4"))
                .thenReturn("https://media.example/lesson.mp4?signature=temporary");

        MediaDetailResponse response = mediaService.getDetail(42L, 7L);

        assertThat(response.playbackUrl()).contains("signature=temporary");
        assertThat(response.filename()).isEqualTo("lesson.mp4");
    }

    @Test
    void externalMediaDoesNotAskMinioForAPlaybackUrl() {
        MediaFile media = new MediaFile();
        media.setId(42L);
        media.setUserId(7L);
        media.setFilename("external video");
        media.setSourceType("EXTERNAL_URL");
        when(mediaFileMapper.selectOne(any())).thenReturn(media);

        MediaDetailResponse response = mediaService.getDetail(42L, 7L);

        assertThat(response.playbackUrl()).isNull();
        assertThat(response.localPlaybackAvailable()).isFalse();
        verify(objectStorageService, never()).createReadUrl(any());
    }

    @Test
    void deleteRemovesTheVideoAndItsVisualEvidenceObjects() {
        MediaFile media = new MediaFile();
        media.setId(42L);
        media.setUserId(7L);
        media.setObjectKey("objects/lesson.mp4");
        when(mediaFileMapper.selectOne(any())).thenReturn(media);

        mediaService.delete(42L, 7L);

        verify(objectStorageService).remove("objects/lesson.mp4");
        verify(visualEvidenceService).removeObjects(42L);
        verify(mediaFileMapper).deleteById(42L);
    }

    @Test
    void deletionOfHashedMediaUsesTheSameContentLockAsRegistration() {
        MediaFile media = new MediaFile();
        media.setId(42L);
        media.setUserId(7L);
        media.setObjectKey("objects/lesson.mp4");
        media.setContentSha256("abc123");
        when(mediaFileMapper.selectOne(any())).thenReturn(media);
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock("lock:media-content:7:abc123")).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        mediaService.delete(42L, 7L);

        verify(lock).lock();
        verify(lock).unlock();
        verify(objectStorageService).remove("objects/lesson.mp4");
    }
}
