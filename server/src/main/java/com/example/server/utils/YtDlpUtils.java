package com.example.server.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class YtDlpUtils {

    private static final long PROCESS_TIMEOUT_SECONDS = 90;
    private static final long MAX_METADATA_BYTES = 20L * 1024 * 1024;

    private final String ytDlpPath;
    private final String proxy;

    public YtDlpUtils(
            @Value("${tool.ytdlp.path}") String ytDlpPath,
            @Value("${tool.ytdlp.proxy:}") String proxy
    ) {
        this.ytDlpPath = ytDlpPath;
        this.proxy = proxy;
    }

    public String inspectVideo(String canonicalUrl) throws IOException, InterruptedException, TimeoutException {
        Path output = Files.createTempFile("vediomind-ytdlp-", ".json");
        Path error = Files.createTempFile("vediomind-ytdlp-", ".log");
        try {
            List<String> command = new ArrayList<>(List.of(
                    ytDlpPath,
                    "--no-update",
                    "--dump-single-json",
                    "--skip-download",
                    "--no-playlist",
                    "--socket-timeout", "20",
                    "--retries", "1"
            ));
            if (proxy != null && !proxy.isBlank()) {
                command.add("--proxy");
                command.add(proxy);
            }
            command.add(canonicalUrl);

            Process process = new ProcessBuilder(command)
                    .redirectOutput(output.toFile())
                    .redirectError(error.toFile())
                    .start();
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new TimeoutException("yt-dlp metadata inspection timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("yt-dlp metadata inspection failed: " + readLimited(error, 4_000));
            }
            if (Files.size(output) > MAX_METADATA_BYTES) {
                throw new IOException("yt-dlp metadata response is too large");
            }
            return Files.readString(output, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(output);
            Files.deleteIfExists(error);
        }
    }

    private String readLimited(Path path, int maxCharacters) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return new String(input.readNBytes(maxCharacters), StandardCharsets.UTF_8).trim();
        }
    }
}
