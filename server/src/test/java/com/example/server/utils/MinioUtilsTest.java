package com.example.server.utils;

import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MinioUtilsTest {

    private final MinioUtils minioUtils = new MinioUtils(mock(MinioClient.class), "media");

    @Test
    void infersVideoTypeWhenTheUploaderOnlySendsOctetStream() {
        assertThat(minioUtils.resolveContentType("application/octet-stream", "lesson.mp4"))
                .isEqualTo("video/mp4");
    }

    @Test
    void preservesASpecificUploaderContentType() {
        assertThat(minioUtils.resolveContentType("video/webm", "lesson.webm"))
                .isEqualTo("video/webm");
    }
}
