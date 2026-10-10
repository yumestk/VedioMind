package com.example.server.service.external;

import com.example.server.service.ai.TranscriptSegmentDraft;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class BilibiliVideoResolver implements ExternalVideoResolver {

    private static final Set<String> HOSTS = Set.of("bilibili.com", "www.bilibili.com", "m.bilibili.com", "b23.tv");
    private static final Pattern BVID_PATTERN = Pattern.compile("(?:^|/)video/(BV[0-9A-Za-z]{10})(?:/|$)");
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_SEGMENTS = 20_000;

    private final ExternalVideoHttpClient httpClient;

    public BilibiliVideoResolver(ExternalVideoHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public boolean supports(URI uri) {
        return uri.getHost() != null && HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    @Override
    public ResolvedExternalVideo resolve(URI originalUri) {
        try {
            URI resolvedUri = resolveShortLink(originalUri);
            String bvid = extractBvid(resolvedUri);
            String canonicalUrl = "https://www.bilibili.com/video/" + bvid;

            JsonNode metadataRoot = httpClient.getJson(
                    URI.create("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid),
                    canonicalUrl
            );
            JsonNode metadata = requireData(metadataRoot, "视频信息");
            long aid = requiredLong(metadata, "aid");
            long cid = requiredLong(metadata, "cid");

            JsonNode subtitleRoot = httpClient.getJson(
                    URI.create("https://api.bilibili.com/x/v2/dm/view?aid=" + aid + "&oid=" + cid + "&type=1"),
                    canonicalUrl
            );
            JsonNode subtitles = requireData(subtitleRoot, "字幕信息").path("subtitle").path("subtitles");
            JsonNode selected = selectSubtitle(subtitles);
            if (selected == null) {
                throw ExternalVideoException.subtitleUnavailable();
            }

            URI subtitleUri = subtitleUri(selected.path("subtitle_url").asText());
            JsonNode subtitleBody = httpClient.getJson(subtitleUri, canonicalUrl).path("body");
            List<TranscriptSegmentDraft> segments = parseSegments(subtitleBody);
            if (segments.isEmpty()) {
                throw ExternalVideoException.subtitleUnavailable();
            }

            String language = selected.path("lan").asText("unknown");
            boolean aiGenerated = language.startsWith("ai-") || selected.path("type").asInt() == 1;
            return new ResolvedExternalVideo(
                    "BILIBILI",
                    bvid,
                    requiredText(metadata, "title"),
                    secureUrl(metadata.path("pic").asText(null)),
                    metadata.path("duration").isNumber() ? metadata.path("duration").asLong() * 1000 : null,
                    canonicalUrl,
                    aiGenerated ? "BILIBILI_AI" : "BILIBILI_MANUAL",
                    language,
                    segments
            );
        } catch (ExternalVideoException exception) {
            throw exception;
        } catch (SocketTimeoutException exception) {
            throw ExternalVideoException.timeout(exception);
        } catch (IOException exception) {
            throw ExternalVideoException.platformUnavailable(exception);
        } catch (RuntimeException exception) {
            throw ExternalVideoException.platformUnavailable(exception);
        }
    }

    private URI resolveShortLink(URI uri) throws IOException {
        URI current = uri;
        for (int redirect = 0; "b23.tv".equalsIgnoreCase(current.getHost()); redirect++) {
            if (redirect >= MAX_REDIRECTS) {
                throw ExternalVideoException.invalidUrl("Bilibili 短链接重定向次数过多。");
            }
            current = httpClient.getRedirect(current);
            if (!isHttp(current) || !supports(current)) {
                throw ExternalVideoException.invalidUrl("Bilibili 短链接跳转到了不受支持的地址。");
            }
        }
        return current;
    }

    private String extractBvid(URI uri) {
        Matcher matcher = BVID_PATTERN.matcher(uri.getPath());
        if (!matcher.find()) {
            throw ExternalVideoException.invalidUrl("无法从 Bilibili 链接中识别 BV 号。");
        }
        return matcher.group(1);
    }

    private JsonNode requireData(JsonNode root, String label) {
        if (root.path("code").asInt(-1) != 0 || root.path("data").isMissingNode()) {
            throw ExternalVideoException.invalidUrl("Bilibili 未返回有效的" + label + "。");
        }
        return root.path("data");
    }

    private JsonNode selectSubtitle(JsonNode subtitles) {
        if (!subtitles.isArray() || subtitles.isEmpty()) {
            return null;
        }
        List<JsonNode> candidates = new ArrayList<>();
        subtitles.forEach(candidates::add);
        return candidates.stream()
                .min(Comparator.comparingInt(this::subtitlePriority))
                .orElse(null);
    }

    private int subtitlePriority(JsonNode subtitle) {
        String language = subtitle.path("lan").asText("").toLowerCase(Locale.ROOT);
        boolean aiGenerated = language.startsWith("ai-") || subtitle.path("type").asInt() == 1;
        boolean chinese = language.contains("zh");
        if (!aiGenerated && chinese) return 0;
        if (!aiGenerated) return 1;
        if (chinese) return 2;
        return 3;
    }

    private URI subtitleUri(String value) {
        if (value.startsWith("//")) value = "https:" + value;
        if (value.startsWith("http://")) value = "https://" + value.substring("http://".length());
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw ExternalVideoException.platformUnavailable(exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"aisubtitle.hdslb.com".equalsIgnoreCase(uri.getHost())) {
            throw ExternalVideoException.platformUnavailable(new IllegalArgumentException("Unexpected subtitle host"));
        }
        return uri;
    }

    static List<TranscriptSegmentDraft> parseSegments(JsonNode body) {
        if (!body.isArray()) return List.of();
        List<TranscriptSegmentDraft> result = new ArrayList<>();
        for (JsonNode segment : body) {
            String text = segment.path("content").asText("").trim();
            if (text.isBlank()) continue;
            long startMs = Math.max(0, Math.round(segment.path("from").asDouble() * 1000));
            long endMs = Math.max(startMs, Math.round(segment.path("to").asDouble() * 1000));
            result.add(new TranscriptSegmentDraft(startMs, endMs, text));
            if (result.size() > MAX_SEGMENTS) {
                throw ExternalVideoException.platformUnavailable(new IllegalArgumentException("Too many subtitle segments"));
            }
        }
        return result;
    }

    private long requiredLong(JsonNode node, String field) {
        if (!node.path(field).canConvertToLong()) {
            throw ExternalVideoException.invalidUrl("Bilibili 视频信息缺少 " + field + "。");
        }
        return node.path(field).asLong();
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank()) {
            throw ExternalVideoException.invalidUrl("Bilibili 视频信息缺少 " + field + "。");
        }
        return value;
    }

    private boolean isHttp(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
    }

    private String secureUrl(String url) {
        return url != null && url.startsWith("http://") ? "https://" + url.substring(7) : url;
    }
}
