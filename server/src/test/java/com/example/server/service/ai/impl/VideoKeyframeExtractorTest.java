package com.example.server.service.ai.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoKeyframeExtractorTest {

    @Test
    void parsesSelectedFrameTimestampsFromFfmpegShowinfo() {
        String output = """
                [Parsed_showinfo_2 @ 0x1] n:   0 pts:      0 pts_time:0 duration:1
                [Parsed_showinfo_2 @ 0x1] n:   1 pts:   3723 pts_time:3.723 duration:1
                [Parsed_showinfo_2 @ 0x1] n:   2 pts:  30100 pts_time:30.1 duration:1
                """;

        assertThat(VideoKeyframeExtractor.parseTimestamps(output))
                .containsExactly(0L, 3_723L, 30_100L);
    }
}
