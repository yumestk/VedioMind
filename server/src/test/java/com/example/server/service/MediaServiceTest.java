package com.example.server.service;

import com.example.server.dto.MediaDetailResponse;
import com.example.server.dto.MediaListItemResponse;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.YtDlpUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaServiceTest {

    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final MinioUtils minioUtils = mock(MinioUtils.class);
    private final YtDlpUtils ytDlpUtils = mock(YtDlpUtils.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final TranscriptService transcriptService = mock(TranscriptService.class);
    private final ChapterService chapterService = mock(ChapterService.class);
    private final MediaService mediaService = new MediaService(
            mediaFileMapper,
            minioUtils,
            ytDlpUtils,
            redisTemplate,
            new ObjectMapper(),
            transcriptService,
            chapterService
    );

    @Test
    void localUploadPersistsObjectMetadataInsteadOfAPublicUrl() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("lesson.mp4");
        when(file.getContentType()).thenReturn("video/mp4");
        when(file.getSize()).thenReturn(1024L);
        when(minioUtils.upload(file)).thenReturn("objects/lesson.mp4");
        when(minioUtils.resolveContentType("video/mp4", "lesson.mp4")).thenReturn("video/mp4");
        doAnswer(invocation -> {
            MediaFile media = invocation.getArgument(0);
            media.setId(42L);
            return 1;
        }).when(mediaFileMapper).insert(any(MediaFile.class));

        MediaListItemResponse response = mediaService.upload(file, 7L);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.mimeType()).isEqualTo("video/mp4");
        verify(mediaFileMapper).insert(any(MediaFile.class));
        verify(minioUtils, never()).remove("objects/lesson.mp4");
    }

    @Test
    void uploadRemovesTheObjectWhenDatabasePersistenceFails() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("lesson.mp4");
        when(file.getContentType()).thenReturn("video/mp4");
        when(minioUtils.upload(file)).thenReturn("objects/lesson.mp4");
        when(minioUtils.resolveContentType("video/mp4", "lesson.mp4")).thenReturn("video/mp4");
        when(mediaFileMapper.insert(any(MediaFile.class))).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> mediaService.upload(file, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to upload media");

        verify(minioUtils).remove("objects/lesson.mp4");
    }

    @Test
    void detailUsesAShortLivedPlaybackUrl() {
        MediaFile media = new MediaFile();
        media.setId(42L);
        media.setUserId(7L);
        media.setFilename("lesson.mp4");
        media.setObjectKey("objects/lesson.mp4");
        when(mediaFileMapper.selectOne(any())).thenReturn(media);
        when(minioUtils.createReadUrl("objects/lesson.mp4"))
                .thenReturn("https://media.example/lesson.mp4?signature=temporary");

        MediaDetailResponse response = mediaService.getDetail(42L, 7L);

        assertThat(response.playbackUrl()).contains("signature=temporary");
        assertThat(response.filename()).isEqualTo("lesson.mp4");
    }
}
