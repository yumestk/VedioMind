package com.example.server.service;

import com.example.server.dto.CompleteMediaUploadRequest;
import com.example.server.dto.CompleteUploadPartRequest;
import com.example.server.dto.InitiateMediaUploadRequest;
import com.example.server.dto.MediaListItemResponse;
import com.example.server.entity.MediaFile;
import com.example.server.entity.MediaUploadSession;
import com.example.server.mapper.MediaUploadSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaUploadServiceTest {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024 * 1024;

    private final MediaUploadSessionMapper sessionMapper = mock(MediaUploadSessionMapper.class);
    private final ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
    private final MediaService mediaService = mock(MediaService.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final MediaUploadService uploadService = new MediaUploadService(
            sessionMapper,
            objectStorageService,
            mediaService,
            redissonClient,
            MAX_FILE_SIZE
    );

    @BeforeEach
    void setUp() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void initiatesADirectMultipartUploadForVideoFiles() {
        when(objectStorageService.resolveContentType("video/mp4", "lesson.mp4")).thenReturn("video/mp4");
        when(objectStorageService.createObjectKey("lesson.mp4")).thenReturn("media/random.mp4");
        when(objectStorageService.createMultipartUpload("media/random.mp4", "video/mp4"))
                .thenReturn("upload-1");

        var response = uploadService.initiate(
                new InitiateMediaUploadRequest("lesson.mp4", "video/mp4", 1024L),
                7L
        );

        assertThat(response.partSize()).isEqualTo(MediaUploadService.PART_SIZE);
        ArgumentCaptor<MediaUploadSession> captor = ArgumentCaptor.forClass(MediaUploadSession.class);
        verify(sessionMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getStatus()).isEqualTo("UPLOADING");
    }

    @Test
    void rejectsFilesAboveTheConfiguredLimitBeforeCreatingStorageState() {
        assertThatThrownBy(() -> uploadService.initiate(
                new InitiateMediaUploadRequest("huge.mp4", "video/mp4", MAX_FILE_SIZE + 1),
                7L
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void verifiesEveryPartAndCompletesIdempotentMediaRegistration() {
        long fileSize = MediaUploadService.PART_SIZE * 2 + 5;
        MediaUploadSession session = session(fileSize);
        when(sessionMapper.selectById("session-1")).thenReturn(session);
        when(objectStorageService.objectSize("media/random.mp4")).thenReturn(null, fileSize);
        List<ObjectStorageService.StoredPart> storedParts = List.of(
                new ObjectStorageService.StoredPart(1, "\"etag-1\"", MediaUploadService.PART_SIZE),
                new ObjectStorageService.StoredPart(2, "\"etag-2\"", MediaUploadService.PART_SIZE),
                new ObjectStorageService.StoredPart(3, "\"etag-3\"", 5)
        );
        when(objectStorageService.listParts("media/random.mp4", "upload-1")).thenReturn(storedParts);

        MediaFile media = new MediaFile();
        media.setId(42L);
        media.setUserId(7L);
        media.setFilename("lesson.mp4");
        media.setSourceType("LOCAL_UPLOAD");
        media.setAnalysisMode("STUDY");
        MediaListItemResponse mediaResponse = MediaListItemResponse.from(media);
        when(mediaService.registerLocalUpload(
                7L, "lesson.mp4", "media/random.mp4", "video/mp4", fileSize
        )).thenReturn(mediaResponse);

        MediaListItemResponse response = uploadService.complete(
                "session-1",
                new CompleteMediaUploadRequest(List.of(
                        new CompleteUploadPartRequest(1, "etag-1"),
                        new CompleteUploadPartRequest(2, "etag-2"),
                        new CompleteUploadPartRequest(3, "etag-3")
                )),
                7L
        );

        assertThat(response.id()).isEqualTo(42L);
        verify(objectStorageService).completeMultipartUpload("media/random.mp4", "upload-1", storedParts);
        verify(sessionMapper).updateById(session);
        assertThat(session.getStatus()).isEqualTo("COMPLETED");
        assertThat(session.getMediaId()).isEqualTo(42L);
    }

    private MediaUploadSession session(long fileSize) {
        MediaUploadSession session = new MediaUploadSession();
        session.setId("session-1");
        session.setUserId(7L);
        session.setUploadId("upload-1");
        session.setObjectKey("media/random.mp4");
        session.setFilename("lesson.mp4");
        session.setMimeType("video/mp4");
        session.setFileSize(fileSize);
        session.setPartSize(MediaUploadService.PART_SIZE);
        session.setStatus("UPLOADING");
        return session;
    }
}
