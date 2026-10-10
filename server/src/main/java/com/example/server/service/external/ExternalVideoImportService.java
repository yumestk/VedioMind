package com.example.server.service.external;

import com.example.server.entity.MediaFile;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

@Service
public class ExternalVideoImportService {

    private static final int MAX_URL_LENGTH = 2_000;

    private final List<ExternalVideoResolver> resolvers;
    private final ExternalVideoStore store;

    public ExternalVideoImportService(List<ExternalVideoResolver> resolvers, ExternalVideoStore store) {
        this.resolvers = resolvers;
        this.store = store;
    }

    public MediaFile importVideo(String rawUrl, Long userId) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw ExternalVideoException.invalidUrl("视频链接不能为空。");
        }
        if (rawUrl.length() > MAX_URL_LENGTH) {
            throw ExternalVideoException.invalidUrl("视频链接过长。");
        }

        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException exception) {
            throw ExternalVideoException.invalidUrl("视频链接格式不正确。");
        }
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw ExternalVideoException.invalidUrl("请输入有效的 HTTP/HTTPS 视频链接。");
        }

        ExternalVideoResolver resolver = resolvers.stream()
                .filter(candidate -> candidate.supports(uri))
                .findFirst()
                .orElseThrow(ExternalVideoException::unsupportedPlatform);
        ResolvedExternalVideo resolved = resolver.resolve(uri);
        return store.save(userId, resolved);
    }
}
