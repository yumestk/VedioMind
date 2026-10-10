package com.example.server.service.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class ExternalVideoHttpClient {

    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private final ObjectMapper objectMapper;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(20))
            .callTimeout(Duration.ofSeconds(30))
            .followRedirects(false)
            .build();

    public ExternalVideoHttpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode getJson(URI uri, String referer) throws IOException {
        Request.Builder request = new Request.Builder()
                .url(uri.toString())
                .header("User-Agent", USER_AGENT);
        if (referer != null && !referer.isBlank()) {
            request.header("Referer", referer);
        }

        try (Response response = client.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("External platform returned HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("External platform returned an empty response");
            }
            byte[] bytes = body.byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new IOException("External platform response is too large");
            }
            return objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    public URI getRedirect(URI uri) throws IOException {
        Request request = new Request.Builder()
                .url(uri.toString())
                .header("User-Agent", USER_AGENT)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isRedirect()) {
                throw new IOException("Short link did not return a redirect");
            }
            String location = response.header("Location");
            if (location == null || location.isBlank()) {
                throw new IOException("Short link redirect is missing a target");
            }
            return uri.resolve(location);
        }
    }
}
