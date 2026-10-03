package com.example.server.service.ai.impl;

import com.example.server.service.ai.AudioExtractor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class FfmpegAudioExtractor implements AudioExtractor {

    private static final int SAMPLE_RATE = 16_000;

    private final String ffmpegDirectory;

    public FfmpegAudioExtractor(@Value("${tool.ffmpeg.dir}") String ffmpegDirectory) {
        this.ffmpegDirectory = ffmpegDirectory;
    }

    @Override
    public File extract(String videoUrl) {
        if (videoUrl == null || videoUrl.isBlank()) {
            throw new IllegalArgumentException("Video URL must not be empty");
        }

        File outputFile = new File(
                System.getProperty("java.io.tmpdir"),
                "asr-" + UUID.randomUUID() + ".pcm"
        );

        List<String> command = new ArrayList<>();
        command.add(resolveFfmpegExecutable());
        command.add("-y");
        command.add("-i");
        command.add(videoUrl);
        command.add("-vn");
        command.add("-ac");
        command.add("1");
        command.add("-ar");
        command.add(String.valueOf(SAMPLE_RATE));
        command.add("-acodec");
        command.add("pcm_s16le");
        command.add("-f");
        command.add("s16le");
        command.add(outputFile.getAbsolutePath());

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            Process process = processBuilder.start();
            boolean finished = process.waitFor(15, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg audio extraction timed out");
            }
            if (process.exitValue() != 0 || !outputFile.isFile() || outputFile.length() == 0) {
                throw new IllegalStateException(
                        "FFmpeg audio extraction failed with exit code " + process.exitValue()
                );
            }
            return outputFile;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            outputFile.delete();
            throw new IllegalStateException("FFmpeg audio extraction was interrupted", exception);
        } catch (Exception exception) {
            outputFile.delete();
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("Unable to extract audio", exception);
        }
    }

    private String resolveFfmpegExecutable() {
        if (ffmpegDirectory == null || ffmpegDirectory.isBlank()) {
            return "ffmpeg";
        }
        return Path.of(ffmpegDirectory, "ffmpeg").toString();
    }
}
