package com.example.server.service.ai.impl;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;
import static org.junit.jupiter.api.Assertions.*;

class OcrParallelTest {
    @Test
    void keepsOriginalOrderIncludingFailures() {
        var ocr = new TesseractOcrService("unused") {
            @Override
            public OcrResult recognize(Path path) {
                if (path.toString().equals("bad")) throw new IllegalStateException("test failure");
                return new OcrResult(path.toString(), 91, true);
            }
        };
        var paths = List.of(Path.of("first"), Path.of("bad"), Path.of("third"));
        var single = TesseractOcrService.recognizeOrdered(ocr, paths, 1, new Semaphore(1));
        var multi = TesseractOcrService.recognizeOrdered(ocr, paths, 3, new Semaphore(3));
        assertEquals(single.stream().map(TesseractOcrService.FrameOcr::status).toList(),
                multi.stream().map(TesseractOcrService.FrameOcr::status).toList());
        assertEquals(single.stream().map(TesseractOcrService.FrameOcr::result).toList(),
                multi.stream().map(TesseractOcrService.FrameOcr::result).toList());
    }
}
