package com.example.server.entity;

import java.util.Set;

public enum AnalysisJobStatus {
    QUEUED,
    PREPARING,
    EXTRACTING_AUDIO,
    TRANSCRIBING,
    PROCESSING_VISUAL_EVIDENCE,
    BUILDING_KNOWLEDGE_INDEX,
    SUMMARIZING,
    GENERATING_CHAPTERS,
    RETRYING,
    WAITING_FOR_QUOTA,
    SUCCEEDED,
    FAILED;

    private static final Set<AnalysisJobStatus> TERMINAL = Set.of(SUCCEEDED, FAILED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
