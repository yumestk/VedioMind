package com.example.server.service.external;

import com.example.server.service.ai.TranscriptSegmentDraft;
import com.example.server.utils.YtDlpUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Component
public class YoutubeVideoResolver implements ExternalVideoResolver {

    private static final Set<String> HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be");
    private static final Set<String> CAPTION_HOSTS = Set.of("youtube.com", "www.youtube.com");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final int MAX_SEGMENTS = 20_000;

    private final YtDlpUtils ytDlpUtils;
    private final ExternalVideoHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public YoutubeVideoResolver(
            YtDlpUtils ytDlpUtils,
            ExternalVideoHttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.ytDlpUtils = ytDlpUtils;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(URI uri) {
        return uri.getHost() != null && HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    @Override
    public ResolvedExternalVideo resolve(URI uri) {
        String videoId = extractVideoId(uri);
        String canonicalUrl = "https://www.youtube.com/watch?v=" + videoId;
        try {
            JsonNode metadata = objectMapper.readTree(ytDlpUtils.inspectVideo(canonicalUrl));
            if (!videoId.equals(metadata.path("id").asText())) {
                throw ExternalVideoException.invalidUrl("YouTube 返回的视频 ID 与链接不一致。");
            }

            String originalLanguage = metadata.path("language").asText("");
            SelectedCaption selected = selectCaption(metadata.path("subtitles"), originalLanguage, false);
            if (selected == null) {
                selected = selectCaption(metadata.path("automatic_captions"), originalLanguage, true);
            }
            if (selected == null) {
                throw ExternalVideoException.subtitleUnavailable();
            }

            URI captionUri = URI.create(selected.url());
            if (!"https".equalsIgnoreCase(captionUri.getScheme())
                    || captionUri.getHost() == null
                    || !CAPTION_HOSTS.contains(captionUri.getHost().toLowerCase(Locale.ROOT))) {
                throw ExternalVideoException.platformUnavailable(new IllegalArgumentException("Unexpected caption host"));
            }
            List<TranscriptSegmentDraft> segments = parseSegments(httpClient.getJson(captionUri, canonicalUrl).path("events"));
            if (segments.isEmpty()) {
                throw ExternalVideoException.subtitleUnavailable();
            }

            String title = metadata.path("title").asText("").trim();
            if (title.isBlank()) {
                throw ExternalVideoException.invalidUrl("YouTube 未返回视频标题。");
            }
            return new ResolvedExternalVideo(
                    "YOUTUBE",
                    videoId,
                    title,
                    metadata.path("thumbnail").asText(null),
                    metadata.path("duration").isNumber() ? Math.round(metadata.path("duration").asDouble() * 1000) : null,
                    canonicalUrl,
                    selected.automatic() ? "YOUTUBE_AUTO" : "YOUTUBE_MANUAL",
                    selected.language().replace("-orig", ""),
                    segments
            );
        } catch (ExternalVideoException exception) {
            throw exception;
        } catch (TimeoutException | SocketTimeoutException exception) {
            throw ExternalVideoException.timeout(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ExternalVideoException.platformUnavailable(exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw ExternalVideoException.platformUnavailable(exception);
        }
    }

    private String extractVideoId(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String candidate = null;
        if ("youtu.be".equals(host)) {
            String[] parts = uri.getPath().split("/");
            if (parts.length > 1) candidate = parts[1];
        } else if ("/watch".equals(uri.getPath())) {
            candidate = queryParameters(uri.getRawQuery()).get("v");
        } else {
            String[] parts = uri.getPath().split("/");
            if (parts.length > 2 && ("shorts".equals(parts[1]) || "embed".equals(parts[1]))) {
                candidate = parts[2];
            }
        }
        if (candidate == null || !VIDEO_ID.matcher(candidate).matches()) {
            throw ExternalVideoException.invalidUrl("无法从 YouTube 链接中识别视频 ID。");
        }
        return candidate;
    }

    private Map<String, String> queryParameters(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return Map.of();
        Map<String, String> parameters = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            parameters.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : ""
            );
        }
        return parameters;
    }

    private SelectedCaption selectCaption(JsonNode captions, String originalLanguage, boolean automatic) {
        if (!captions.isObject() || captions.isEmpty()) return null;
        List<String> languages = new ArrayList<>();
        captions.fieldNames().forEachRemaining(languages::add);
        languages.sort((left, right) -> Integer.compare(
                languagePriority(left, originalLanguage, automatic),
                languagePriority(right, originalLanguage, automatic)
        ));

        for (String language : languages) {
            JsonNode formats = captions.path(language);
            if (!formats.isArray()) continue;
            for (JsonNode format : formats) {
                if ("json3".equals(format.path("ext").asText()) && !format.path("url").asText("").isBlank()) {
                    return new SelectedCaption(language, format.path("url").asText(), automatic);
                }
            }
        }
        return null;
    }

    private int languagePriority(String language, String originalLanguage, boolean automatic) {
        String normalized = language.toLowerCase(Locale.ROOT);
        String original = originalLanguage.toLowerCase(Locale.ROOT);
        String originalBase = original.contains("-") ? original.substring(0, original.indexOf('-')) : original;
        boolean matchesOriginal = !original.isBlank()
                && (normalized.equals(original) || normalized.equals(originalBase) || normalized.equals(originalBase + "-orig"));
        if (!automatic && normalized.startsWith("zh")) return 0;
        if (!automatic && matchesOriginal) return 1;
        if (!automatic) return 2;
        if (normalized.endsWith("-orig") && matchesOriginal) return 0;
        if (normalized.endsWith("-orig")) return 1;
        if (matchesOriginal) return 2;
        if (normalized.startsWith("zh")) return 3;
        if (normalized.startsWith("en")) return 4;
        return 5;
    }

    static List<TranscriptSegmentDraft> parseSegments(JsonNode events) {
        if (!events.isArray()) return List.of();
        List<TranscriptSegmentDraft> result = new ArrayList<>();
        for (JsonNode event : events) {
            JsonNode pieces = event.path("segs");
            if (!pieces.isArray()) continue;
            StringBuilder text = new StringBuilder();
            for (JsonNode piece : pieces) {
                text.append(piece.path("utf8").asText(""));
            }
            String normalized = text.toString().replaceAll("\\s+", " ").trim();
            if (normalized.isBlank()) continue;
            long startMs = Math.max(0, event.path("tStartMs").asLong());
            long durationMs = event.path("dDurationMs").asLong(2_000);
            long endMs = startMs + Math.max(1, durationMs);
            result.add(new TranscriptSegmentDraft(startMs, endMs, normalized));
            if (result.size() > MAX_SEGMENTS) {
                throw ExternalVideoException.platformUnavailable(new IllegalArgumentException("Too many caption segments"));
            }
        }
        return result;
    }

    private record SelectedCaption(String language, String url, boolean automatic) {
    }
}
