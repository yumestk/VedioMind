package com.example.server.strategy.impl;

import com.example.server.strategy.AiAnalysisStrategy;
import com.example.server.utils.AliyunAsrUtils;
import com.example.server.utils.DeepSeekUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component("defaultAiStrategy")
public class AliyunDeepSeekStrategy implements AiAnalysisStrategy {

    private final AliyunAsrUtils aliyunAsrUtils;
    private final DeepSeekUtils deepSeekUtils;

    public AliyunDeepSeekStrategy(AliyunAsrUtils aliyunAsrUtils, DeepSeekUtils deepSeekUtils) {
        this.aliyunAsrUtils = aliyunAsrUtils;
        this.deepSeekUtils = deepSeekUtils;
    }

    @Override
    public String transcribe(File audioFile) {
        return aliyunAsrUtils.audioToText(audioFile.getAbsolutePath());
    }

    @Override
    public String generateSummary(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            throw new IllegalArgumentException("Transcript is empty");
        }
        return deepSeekUtils.analyzeContent("请对以下视频提取的文字进行总结，不需要废话，直接列出核心观点：\n" + transcript);
    }

    @Override
    public File extractAudio(String inputPath) {
        if (inputPath == null || inputPath.isBlank()) {
            throw new IllegalArgumentException("Video path is empty");
        }
        if (!inputPath.startsWith("http")) {
            File localFile = new File(inputPath);
            if (!localFile.exists()) {
                throw new IllegalArgumentException("Video file does not exist: " + inputPath);
            }
        }

        String outputMp3Path = System.getProperty("java.io.tmpdir") + File.separator + "temp_" + UUID.randomUUID() + ".mp3";
        File outputFile = new File(outputMp3Path);

        try {
            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-y");
            command.add("-i");
            command.add(inputPath);
            command.add("-vn");
            command.add("-acodec");
            command.add("libmp3lame");
            command.add("-q:a");
            command.add("2");
            command.add(outputMp3Path);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            Process process = pb.start();
            boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg audio extraction timed out");
            }
            if (process.exitValue() != 0 || !outputFile.exists()) {
                throw new IllegalStateException("FFmpeg audio extraction failed with exit code " + process.exitValue());
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
}
