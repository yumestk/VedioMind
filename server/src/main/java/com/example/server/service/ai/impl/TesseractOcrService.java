package com.example.server.service.ai.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class TesseractOcrService {

    private static final double MIN_TOKEN_CONFIDENCE = 50;
    private static final double MIN_RESULT_CONFIDENCE = 60;
    private static final int MIN_RELIABLE_CHARACTERS = 3;

    private final String executable;

    public TesseractOcrService(@Value("${tool.tesseract.path:tesseract}") String executable) {
        this.executable = executable;
    }

    public void ensureReady() {
        String languages = run(List.of(executable, "--list-langs"), 30);
        if (!languages.lines().anyMatch("chi_sim"::equals)
                || !languages.lines().anyMatch("eng"::equals)) {
            throw new IllegalStateException("Tesseract requires the chi_sim and eng language packs");
        }
    }

    public OcrResult recognize(Path imagePath) {
        return parseTsv(run(List.of(
                executable,
                imagePath.toString(),
                "stdout",
                "-l", "chi_sim+eng",
                "--psm", "11",
                "tsv"
        ), 120));
    }

    static OcrResult parseTsv(String tsv) {
        Map<String, List<String>> reliableLines = new LinkedHashMap<>();
        double weightedConfidence = 0;
        int detectedCharacters = 0;
        int reliableCharacters = 0;

        for (String row : tsv.lines().skip(1).toList()) {
            String[] columns = row.split("\t", -1);
            if (columns.length < 12 || columns[11].isBlank()) {
                continue;
            }

            double confidence;
            try {
                confidence = Double.parseDouble(columns[10]);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (confidence < 0) {
                continue;
            }

            String text = columns[11].trim();
            int characterCount = text.codePointCount(0, text.length());
            detectedCharacters += characterCount;
            weightedConfidence += confidence * characterCount;
            if (confidence < MIN_TOKEN_CONFIDENCE) {
                continue;
            }

            reliableCharacters += characterCount;
            String lineKey = String.join(":", columns[1], columns[2], columns[3], columns[4]);
            reliableLines.computeIfAbsent(lineKey, ignored -> new java.util.ArrayList<>()).add(text);
        }

        double confidence = detectedCharacters == 0
                ? 0
                : Math.round(weightedConfidence / detectedCharacters * 100) / 100.0;
        String text = normalize(reliableLines.values().stream()
                .map(words -> String.join(" ", words))
                .collect(Collectors.joining("\n")));
        boolean reliable = confidence >= MIN_RESULT_CONFIDENCE
                && reliableCharacters >= MIN_RELIABLE_CHARACTERS
                && !text.isBlank();
        return new OcrResult(reliable ? text : "", confidence, detectedCharacters > 0);
    }

    static String normalize(String text) {
        return text
                .replaceAll("[\\t ]+", " ")
                .replaceAll(" +(?=\\R)", "")
                .replaceAll("(?:\\R\\s*){3,}", "\n\n")
                .trim();
    }

    private String run(List<String> command, int timeoutSeconds) {
        Path stdout = null;
        Path stderr = null;
        Process process = null;
        try {
            stdout = Files.createTempFile("tesseract-stdout-", ".log");
            stderr = Files.createTempFile("tesseract-stderr-", ".log");
            process = new ProcessBuilder(command)
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Tesseract OCR timed out");
            }
            if (process.exitValue() != 0) {
                String error = Files.readString(stderr).trim();
                throw new IllegalStateException(
                        error.isBlank() ? "Tesseract OCR failed" : "Tesseract OCR failed: " + error
                );
            }
            return Files.readString(stdout);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException("Tesseract OCR was interrupted", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("Unable to run Tesseract; install it and chi_sim+eng first", exception);
        } finally {
            delete(stdout);
            delete(stderr);
        }
    }

    private void delete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Temporary OCR output is best-effort cleanup.
        }
    }

    public record OcrResult(String text, double confidence, boolean detectedText) {
    }
}
