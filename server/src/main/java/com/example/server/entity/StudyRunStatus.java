package com.example.server.entity;

import java.util.List;

/** Lifecycle only; retrieval/drafting/revision are phases. */
public final class StudyRunStatus {
    private StudyRunStatus() {}

    public static final List<String> TERMINAL =
            List.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED", "NEEDS_INPUT");
    public static final String TERMINAL_SQL = "('" + String.join("','", TERMINAL) + "')";
    public static final String PENDING_SQL =
            "('QUEUED','WAITING_FOR_QUOTA','RETRY_WAIT','WAITING_OPERATION')";

    public static boolean terminal(String status) {
        return TERMINAL.contains(status);
    }
}
