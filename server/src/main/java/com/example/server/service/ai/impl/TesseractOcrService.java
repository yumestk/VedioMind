package com.example.server.service.ai.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class TesseractOcrService {

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

    public String recognize(Path imagePath) {
        return normalize(run(List.of(
                executable,
                imagePath.toString(),
                "stdout",
                "-l", "chi_sim+eng",
                "--psm", "11"
        ), 120));
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
}
