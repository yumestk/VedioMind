package com.example.server.strategy;

import java.io.File;

public interface AiAnalysisStrategy {

    File extractAudio(String videoPath);

    String transcribe(File audioFile);

    String generateSummary(String transcript);
}
