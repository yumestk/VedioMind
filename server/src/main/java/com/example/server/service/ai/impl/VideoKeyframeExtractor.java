package com.example.server.service.ai.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class VideoKeyframeExtractor {

    private static final Pattern PTS_TIME = Pattern.compile("pts_time:\\s*([-+]?\\d+(?:\\.\\d+)?)");
    private static final String FILTER = "select='eq(n,0)+gt(scene,0.35)+gte(t-prev_selected_t,30)',"
            + "scale='min(1280,iw)':-2,showinfo";

    private final String ffmpegDirectory;

    public VideoKeyframeExtractor(@Value("${tool.ffmpeg.dir}") String ffmpegDirectory) {
        this.ffmpegDirectory = ffmpegDirectory;
    }

    public Extraction extract(String videoUrl) {
        Path directory = null;
        Process process = null;
        try {
            directory = Files.createTempDirectory("visual-evidence-");
            Path logFile = directory.resolve("ffmpeg.log");
            process = new ProcessBuilder(
                    resolveFfmpegExecutable(),
                    "-y",
                    "-i", videoUrl,
                    "-an",
                    "-vf", FILTER,
                    "-fps_mode", "vfr",
                    "-q:v", "3",
                    directory.resolve("frame-%06d.jpg").toString()
            ).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();

            boolean finished = process.waitFor(45, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg keyframe extraction timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("FFmpeg keyframe extraction failed: " + tail(logFile));
            }

            List<Path> files;
            try (var paths = Files.list(directory)) {
                files = paths
                        .filter(path -> path.getFileName().toString().endsWith(".jpg"))
                        .sorted()
                        .toList();
            }
            List<Long> timestamps = parseTimestamps(Files.readString(logFile));
            if (files.isEmpty() || files.size() != timestamps.size()) {
                throw new IllegalStateException(
                        "FFmpeg returned " + files.size() + " keyframes but "
                                + timestamps.size() + " timestamps"
                );
            }

            List<ExtractedFrame> frames = java.util.stream.IntStream.range(0, files.size())
                    .mapToObj(index -> new ExtractedFrame(files.get(index), timestamps.get(index)))
                    .toList();
            Files.deleteIfExists(logFile);
            return new Extraction(directory, frames);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            deleteDirectory(directory);
            throw new IllegalStateException("FFmpeg keyframe extraction was interrupted", exception);
        } catch (Exception exception) {
            deleteDirectory(directory);
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("Unable to extract video keyframes", exception);
        }
    }

    static List<Long> parseTimestamps(String output) {
        Matcher matcher = PTS_TIME.matcher(output);
        List<Long> timestamps = new java.util.ArrayList<>();
        while (matcher.find()) {
            timestamps.add(Math.round(Double.parseDouble(matcher.group(1)) * 1000));
        }
        return timestamps;
    }

    public static void deleteDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary evidence files are best-effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Temporary evidence files are best-effort cleanup.
        }
    }

    private String resolveFfmpegExecutable() {
        if (ffmpegDirectory == null || ffmpegDirectory.isBlank()) {
            return "ffmpeg";
        }
        return Path.of(ffmpegDirectory, "ffmpeg").toString();
    }

    private String tail(Path logFile) throws IOException {
        String content = Files.readString(logFile).trim();
        return content.length() <= 1000 ? content : content.substring(content.length() - 1000);
    }

    public record Extraction(Path directory, List<ExtractedFrame> frames) {
    }

    public record ExtractedFrame(Path path, long timestampMs) {
    }
}
