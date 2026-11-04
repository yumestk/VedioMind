package com.example.server.service.ai;

import com.example.server.dto.PreparedStudyTask;

/**
 * Lexically scoped worker identity. Never inherit into a different thread or accept it from a
 * client.
 */
public final class StudyExecutionContext implements AutoCloseable {
    private static final ThreadLocal<StudyExecutionContext> CURRENT = new ThreadLocal<>();
    private final StudyExecutionContext previous;
    public final PreparedStudyTask task;
    public final String requestId;
    public final String runId;
    public final long epoch;
    public final String purpose;

    private StudyExecutionContext(
            PreparedStudyTask task, String requestId, String runId, long epoch, String purpose) {
        this.previous = CURRENT.get();
        this.task = task;
        this.requestId = requestId;
        this.runId = runId;
        this.epoch = epoch;
        this.purpose = purpose;
        CURRENT.set(this);
    }

    public static StudyExecutionContext open(PreparedStudyTask task, String runId, long epoch) {
        return new StudyExecutionContext(task, task.requestId(), runId, epoch, "PREPARATION");
    }

    public static StudyExecutionContext preprocessing(String requestId) {
        return new StudyExecutionContext(null, requestId, null, 0, "PREPROCESSING");
    }

    public static StudyExecutionContext purposeIfPresent(String purpose) {
        return current() == null ? null : purpose(purpose);
    }

    public static StudyExecutionContext purpose(String purpose) {
        var current = require();
        return new StudyExecutionContext(
                current.task, current.requestId, current.runId, current.epoch, purpose);
    }

    public static StudyExecutionContext current() {
        return CURRENT.get();
    }

    public static StudyExecutionContext require() {
        var value = CURRENT.get();
        if (value == null) throw new IllegalStateException("Missing host execution context");
        return value;
    }

    public static long epoch(String runId) {
        var value = require();
        if (!runId.equals(value.runId)) throw new GovernanceException("STALE_EXECUTION", "执行身份不匹配");
        return value.epoch;
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    public static class GovernanceException extends IllegalArgumentException {
        private final String code;

        public GovernanceException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
