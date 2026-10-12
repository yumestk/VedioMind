package com.example.server.service.ai.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TesseractOcrServiceTest {

    @Test
    void normalizesOcrWhitespaceWithoutJoiningLines() {
        assertThat(TesseractOcrService.normalize("  第一行   text  \n\n\n  second\tline  "))
                .isEqualTo("第一行 text\n\nsecond line");
    }

    @Test
    void keepsReliableTsvWordsAndReportsWeightedConfidence() {
        TesseractOcrService.OcrResult result = TesseractOcrService.parseTsv("""
                level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext
                5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t95.00\tSymmetry
                5\t1\t1\t1\t1\t2\t12\t0\t10\t10\t85.00\tin
                5\t1\t1\t1\t1\t3\t24\t0\t10\t10\t92.00\tNature
                5\t1\t1\t1\t1\t4\t36\t0\t10\t10\t22.00\t@@
                """);

        assertThat(result.text()).isEqualTo("Symmetry in Nature");
        assertThat(result.confidence()).isEqualTo(84.78);
        assertThat(result.detectedText()).isTrue();
    }

    @Test
    void suppressesDetectedTextWhenOverallConfidenceIsLow() {
        TesseractOcrService.OcrResult result = TesseractOcrService.parseTsv("""
                level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext
                5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t35.00\t@O@
                5\t1\t1\t1\t1\t2\t12\t0\t10\t10\t18.00\tæ»
                """);

        assertThat(result.text()).isEmpty();
        assertThat(result.confidence()).isEqualTo(28.2);
        assertThat(result.detectedText()).isTrue();
    }
}
