package com.example.server.service.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BilibiliVideoResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExternalVideoHttpClient httpClient = mock(ExternalVideoHttpClient.class);
    private final BilibiliVideoResolver resolver = new BilibiliVideoResolver(httpClient);

    @Test
    void importsAiSubtitleWithoutRequestingVideoFormats() throws Exception {
        String sourceUrl = "https://www.bilibili.com/video/BV1jKat6REbW";
        when(httpClient.getJson(
                URI.create("https://api.bilibili.com/x/web-interface/view?bvid=BV1jKat6REbW"),
                sourceUrl
        )).thenReturn(objectMapper.readTree("""
                {"code":0,"data":{"aid":117,"cid":422,"title":"Demo","pic":"http://i0.hdslb.com/cover.jpg","duration":12}}
                """));
        when(httpClient.getJson(
                URI.create("https://api.bilibili.com/x/v2/dm/view?aid=117&oid=422&type=1"),
                sourceUrl
        )).thenReturn(objectMapper.readTree("""
                {"code":0,"data":{"subtitle":{"subtitles":[
                  {"lan":"ai-zh","type":1,"subtitle_url":"//aisubtitle.hdslb.com/demo"}
                ]}}}
                """));
        when(httpClient.getJson(URI.create("https://aisubtitle.hdslb.com/demo"), sourceUrl))
                .thenReturn(objectMapper.readTree("""
                        {"body":[{"from":0.7,"to":1.66,"content":"第一句"}]}
                        """));

        ResolvedExternalVideo result = resolver.resolve(URI.create(sourceUrl));

        assertThat(result.platform()).isEqualTo("BILIBILI");
        assertThat(result.transcriptSource()).isEqualTo("BILIBILI_AI");
        assertThat(result.coverUrl()).startsWith("https://");
        assertThat(result.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.startMs()).isEqualTo(700);
            assertThat(segment.endMs()).isEqualTo(1_660);
            assertThat(segment.text()).isEqualTo("第一句");
        });
    }

    @Test
    void rejectsShortLinksThatRedirectOutsideBilibili() throws Exception {
        URI shortLink = URI.create("https://b23.tv/demo");
        when(httpClient.getRedirect(shortLink)).thenReturn(URI.create("http://127.0.0.1/private"));

        assertThatThrownBy(() -> resolver.resolve(shortLink))
                .isInstanceOf(ExternalVideoException.class)
                .extracting("code")
                .isEqualTo("INVALID_VIDEO_URL");
    }
}
