package com.example.server.entity;

import java.util.Set;

public enum AnalysisJobStatus {
    QUEUED,
    PREPARING,
    EXTRACTING_AUDIO,
    TRANSCRIBING,
    PROCESSING_VISUAL_EVIDENCE,
    SUMMARIZING,
    GENERATING_CHAPTERS,
    RETRYING,
    SUCCEEDED,
    FAILED;

    private static final Set<AnalysisJobStatus> TERMINAL = Set.of(SUCCEEDED, FAILED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
