package com.example.server.service.ai.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TesseractOcrServiceTest {

    @Test
    void normalizesOcrWhitespaceWithoutJoiningLines() {
        assertThat(TesseractOcrService.normalize("  第一行   text  \n\n\n  second\tline  "))
                .isEqualTo("第一行 text\n\nsecond line");
    }
}
