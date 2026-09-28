package com.example.server.entity;

import java.util.Set;

public enum AnalysisJobStatus {
    QUEUED,
    EXTRACTING_AUDIO,
    TRANSCRIBING,
    SUMMARIZING,
    RETRYING,
    SUCCEEDED,
    FAILED;

    private static final Set<AnalysisJobStatus> TERMINAL = Set.of(SUCCEEDED, FAILED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
