package com.example.server.service;

import com.example.server.entity.MediaVisualEvidence;
import com.example.server.entity.VideoKeyframe;
import com.example.server.mapper.MediaVisualEvidenceMapper;
import com.example.server.mapper.VideoKeyframeMapper;
import com.example.server.service.ai.impl.TesseractOcrService;
import com.example.server.service.ai.impl.VideoKeyframeExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VisualEvidenceServiceTest {

    @TempDir
    Path directory;

    @Test
    void persistsAReadyEvidenceSetAndCleansTemporaryFrames() throws Exception {
        MediaVisualEvidenceMapper evidenceMapper = mock(MediaVisualEvidenceMapper.class);
        VideoKeyframeMapper keyframeMapper = mock(VideoKeyframeMapper.class);
        VideoKeyframeExtractor extractor = mock(VideoKeyframeExtractor.class);
        TesseractOcrService ocrService = mock(TesseractOcrService.class);
        ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
        VisualEvidenceService service = new VisualEvidenceService(
                evidenceMapper, keyframeMapper, extractor, ocrService, objectStorageService
        );
        AtomicReference<MediaVisualEvidence> evidence = new AtomicReference<>();
        when(evidenceMapper.selectById(anyLong())).thenAnswer(invocation -> evidence.get());
        doAnswer(invocation -> {
            evidence.set(invocation.getArgument(0));
            return 1;
        }).when(evidenceMapper).insert(any(MediaVisualEvidence.class));
        when(keyframeMapper.selectList(any())).thenReturn(List.of());

        Path extractionDirectory = Files.createDirectory(directory.resolve("extracted"));
        Path imagePath = extractionDirectory.resolve("frame-000001.jpg");
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.WHITE.getRGB());
        ImageIO.write(image, "jpg", imagePath.toFile());
        when(extractor.extract("https://media/video.mp4")).thenReturn(
                new VideoKeyframeExtractor.Extraction(
                        extractionDirectory,
                        List.of(new VideoKeyframeExtractor.ExtractedFrame(imagePath, 12_000))
                )
        );
        when(ocrService.recognize(imagePath)).thenReturn("课程标题");

        try {
            service.processAsync(42L, "https://media/video.mp4").join();
        } finally {
            service.shutdown();
        }

        ArgumentCaptor<VideoKeyframe> frame = ArgumentCaptor.forClass(VideoKeyframe.class);
        verify(keyframeMapper).insert(frame.capture());
        verify(objectStorageService).upload(
                imagePath,
                "evidence/42/frames/12000.jpg",
                "image/jpeg"
        );
        assertThat(frame.getValue().getOcrText()).isEqualTo("课程标题");
        assertThat(frame.getValue().getOcrStatus()).isEqualTo("SUCCEEDED");
        assertThat(evidence.get().getStatus()).isEqualTo("READY");
        assertThat(evidence.get().getFrameCount()).isEqualTo(1);
        assertThat(extractionDirectory).doesNotExist();
    }
}
