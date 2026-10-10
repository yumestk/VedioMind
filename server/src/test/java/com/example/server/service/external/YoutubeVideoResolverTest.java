package com.example.server.service.external;

import com.example.server.utils.YtDlpUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YoutubeVideoResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final YtDlpUtils ytDlpUtils = mock(YtDlpUtils.class);
    private final ExternalVideoHttpClient httpClient = mock(ExternalVideoHttpClient.class);
    private final YoutubeVideoResolver resolver = new YoutubeVideoResolver(ytDlpUtils, httpClient, objectMapper);

    @Test
    void prefersOriginalAutomaticCaptionAndParsesJson3() throws Exception {
        String canonicalUrl = "https://www.youtube.com/watch?v=kNareBFFWtQ";
        when(ytDlpUtils.inspectVideo(canonicalUrl)).thenReturn("""
                {
                  "id":"kNareBFFWtQ",
                  "title":"Demo",
                  "duration":12.5,
                  "thumbnail":"https://i.ytimg.com/demo.jpg",
                  "language":"en-US",
                  "subtitles":{},
                  "automatic_captions":{
                    "zh-Hans":[{"ext":"json3","url":"https://www.youtube.com/caption-zh"}],
                    "en-orig":[{"ext":"json3","url":"https://www.youtube.com/caption-en"}]
                  }
                }
                """);
        when(httpClient.getJson(URI.create("https://www.youtube.com/caption-en"), canonicalUrl))
                .thenReturn(objectMapper.readTree("""
                        {"events":[
                          {"tStartMs":0,"dDurationMs":1000},
                          {"tStartMs":1200,"dDurationMs":2400,"segs":[{"utf8":"Hello"},{"utf8":" world"}]}
                        ]}
                        """));

        ResolvedExternalVideo result = resolver.resolve(URI.create(canonicalUrl));

        assertThat(result.transcriptSource()).isEqualTo("YOUTUBE_AUTO");
        assertThat(result.transcriptLanguage()).isEqualTo("en");
        assertThat(result.durationMs()).isEqualTo(12_500);
        assertThat(result.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.startMs()).isEqualTo(1_200);
            assertThat(segment.endMs()).isEqualTo(3_600);
            assertThat(segment.text()).isEqualTo("Hello world");
        });
    }
}
