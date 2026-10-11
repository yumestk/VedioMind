package com.example.server.service;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ObjectStorageServiceTest {

    private final ObjectStorageService objectStorageService = new ObjectStorageService(
            mock(S3Client.class), mock(S3Presigner.class), "media"
    );

    @Test
    void infersVideoTypeWhenTheUploaderOnlySendsOctetStream() {
        assertThat(objectStorageService.resolveContentType("application/octet-stream", "lesson.mp4"))
                .isEqualTo("video/mp4");
    }

    @Test
    void preservesASpecificUploaderContentType() {
        assertThat(objectStorageService.resolveContentType("video/webm", "lesson.webm"))
                .isEqualTo("video/webm");
    }

    @Test
    void createsOpaqueMediaObjectKeysWithoutUnsafeFilenameCharacters() {
        assertThat(objectStorageService.createObjectKey("../../Lesson.MP4"))
                .matches("media/[0-9a-f-]+\\.mp4");
    }
}
