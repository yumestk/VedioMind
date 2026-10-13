package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.dto.VideoAgentCitation;
import com.example.server.dto.VideoAgentResult;
import com.example.server.dto.VideoAgentRunMessage;
import com.example.server.dto.VideoAgentRunResponse;
import com.example.server.entity.VideoAgentRun;
import com.example.server.entity.VideoAgentStep;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.VideoAgentRunMapper;
import com.example.server.mapper.VideoAgentStepMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class VideoAgentRunService {

    public static final int MAX_GENERATION_CALLS = 9;
    public static final int MAX_RETRIEVAL_CALLS = 6;
    private static final List<String> TERMINAL = List.of("SUCCEEDED", "FAILED");

    private final VideoAgentRunMapper runMapper;
    private final VideoAgentStepMapper stepMapper;
    private final MediaFileMapper mediaFileMapper;

    public VideoAgentRunService(
            VideoAgentRunMapper runMapper,
            VideoAgentStepMapper stepMapper,
            MediaFileMapper mediaFileMapper
    ) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.mediaFileMapper = mediaFileMapper;
    }

    @Transactional
    public void createQueuedRun(VideoAgentRunMessage message) {
        if (runMapper.selectById(message.runId()) != null) {
            return;
        }
        if (mediaFileMapper.selectById(message.mediaId()) == null) {
            throw new IllegalArgumentException("Media does not exist: " + message.mediaId());
        }
        LocalDateTime now = LocalDateTime.now();
        VideoAgentRun run = new VideoAgentRun();
        run.setId(message.runId());
        run.setMediaId(message.mediaId());
        run.setGoal(message.goal());
        run.setContextVersion(message.contextVersion());
        run.setStatus("QUEUED");
        run.setProgress(0);
        run.setRetryCount(0);
        run.setGenerationCalls(0);
        run.setRetrievalCalls(0);
        run.setInputTokens(0);
        run.setOutputTokens(0);
        run.setTokenUsageKnown(true);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);
    }

    public Optional<VideoAgentRun> findById(String runId) {
        return Optional.ofNullable(runMapper.selectById(runId));
    }

    public VideoAgentRun getRequired(String runId) {
        return findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Agent run does not exist: " + runId));
    }

    public List<VideoAgentRunResponse> list(Long mediaId) {
        return runMapper.selectList(new LambdaQueryWrapper<VideoAgentRun>()
                        .eq(VideoAgentRun::getMediaId, mediaId)
                        .orderByDesc(VideoAgentRun::getCreatedAt))
                .stream().map(VideoAgentRunResponse::from).toList();
    }

    public boolean beginAttempt(String runId, int retryCount) {
        VideoAgentRun run = getRequired(runId);
        if (TERMINAL.contains(run.getStatus())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<VideoAgentRun> update = new LambdaUpdateWrapper<VideoAgentRun>()
                .eq(VideoAgentRun::getId, runId)
                .notIn(VideoAgentRun::getStatus, TERMINAL)
                .set(VideoAgentRun::getRetryCount, retryCount)
                .set(VideoAgentRun::getErrorMessage, null)
                .set(VideoAgentRun::getUpdatedAt, now);
        if (run.getStartedAt() == null) {
            update.set(VideoAgentRun::getStartedAt, now)
                    .set(VideoAgentRun::getDeadlineAt, now.plusMinutes(10));
        }
        return runMapper.update(null, update) == 1;
    }

    public void markStage(String runId, String status, int progress) {
        ensureWithinDeadline(runId);
        runMapper.update(null, new LambdaUpdateWrapper<VideoAgentRun>()
                .eq(VideoAgentRun::getId, runId)
                .notIn(VideoAgentRun::getStatus, TERMINAL)
                .set(VideoAgentRun::getStatus, status)
                .set(VideoAgentRun::getProgress, progress)
                .set(VideoAgentRun::getUpdatedAt, LocalDateTime.now()));
    }

    public void reserveGenerationCall(String runId) {
        ensureWithinDeadline(runId);
        int updated = runMapper.update(null, new LambdaUpdateWrapper<VideoAgentRun>()
                .eq(VideoAgentRun::getId, runId)
                .lt(VideoAgentRun::getGenerationCalls, MAX_GENERATION_CALLS)
                .notIn(VideoAgentRun::getStatus, TERMINAL)
                .setSql("generation_calls = generation_calls + 1")
                .set(VideoAgentRun::getUpdatedAt, LocalDateTime.now()));
        if (updated != 1) {
            throw new IllegalStateException("Agent generation call budget exhausted");
        }
    }

    public void reserveRetrievalCall(String runId) {
        ensureWithinDeadline(runId);
        int updated = runMapper.update(null, new LambdaUpdateWrapper<VideoAgentRun>()
                .eq(VideoAgentRun::getId, runId)
                .lt(VideoAgentRun::getRetrievalCalls, MAX_RETRIEVAL_CALLS)
                .notIn(VideoAgentRun::getStatus, TERMINAL)
                .setSql("retrieval_calls = retrieval_calls + 1")
                .set(VideoAgentRun::getUpdatedAt, LocalDateTime.now()));
        if (updated != 1) {
            throw new IllegalStateException("Agent retrieval call budget exhausted");
        }
    }

    public void addTokens(String runId, Integer inputTokens, Integer outputTokens) {
        LambdaUpdateWrapper<VideoAgentRun> update = new LambdaUpdateWrapper<VideoAgentRun>()
                .eq(VideoAgentRun::getId, runId)
                .setSql("input_tokens = input_tokens + " + nonNegative(inputTokens))
                .setSql("output_tokens = output_tokens + " + nonNegative(outputTokens))
                .set(VideoAgentRun::getUpdatedAt, LocalDateTime.now());
        if (inputTokens == null || outputTokens == null) {
            update.set(VideoAgentRun::getTokenUsageKnown, false);
        }
        runMapper.update(null, update);
    }

    public Optional<String> completedStep(String runId, String stage, int round) {
        VideoAgentStep step = stepMapper.selectOne(new LambdaQueryWrapper<VideoAgentStep>()
                .eq(VideoAgentStep::getRunId, runId)
                .eq(VideoAgentStep::getStage, stage)
                .eq(VideoAgentStep::getRoundNo, round)
                .eq(VideoAgentStep::getStatus, "SUCCEEDED"));
        return Optional.ofNullable(step == null ? null : step.getOutputJson());
    }

    @Transactional
    public void saveCompletedStep(
            String runId,
            String stage,
            int round,
            Object input,
            Object output
    ) {
        VideoAgentStep step = stepMapper.selectOne(new LambdaQueryWrapper<VideoAgentStep>()
                .eq(VideoAgentStep::getRunId, runId)
                .eq(VideoAgentStep::getStage, stage)
                .eq(VideoAgentStep::getRoundNo, round));
        LocalDateTime now = LocalDateTime.now();
        if (step == null) {
            step = new VideoAgentStep();
            step.setRunId(runId);
            step.setStage(stage);
            step.setRoundNo(round);
            step.setCreatedAt(now);
        }
        step.setStatus("SUCCEEDED");
        step.setInputJson(input == null ? null : JSON.toJSONString(input));
        step.setOutputJson(JSON.toJSONString(output));
        step.setUpdatedAt(now);
        if (step.getId() == null) {
            stepMapper.insert(step);
        } else {
            stepMapper.updateById(step);
        }
    }

    public void markSucceeded(
            String runId,
            VideoAgentResult result,
            List<VideoAgentCitation> citations
    ) {
        ensureWithinDeadline(runId);
        LocalDateTime now = LocalDateTime.now();
        runMapper.update(null, new LambdaUpdateWrapper<VideoAgentRun>()
                .eq(VideoAgentRun::getId, runId)
                .notIn(VideoAgentRun::getStatus, TERMINAL)
                .set(VideoAgentRun::getStatus, "SUCCEEDED")
                .set(VideoAgentRun::getProgress, 100)
                .set(VideoAgentRun::getResultJson, JSON.toJSONString(result))
                .set(VideoAgentRun::getCitationsJson, JSON.toJSONString(citations))
                .set(VideoAgentRun::getErrorMessage, null)
                .set(VideoAgentRun::getFinishedAt, now)
                .set(VideoAgentRun::getUpdatedAt, now));
    }

    public void markRetrying(String runId, int retryCount, String errorMessage) {
        updateFailure(runId, "RETRYING", retryCount, errorMessage, false);
    }

    public void markFailed(String runId, int retryCount, String errorMessage) {
        updateFailure(runId, "FAILED", retryCount, errorMessage, true);
    }

    private void updateFailure(
            String runId,
            String status,
            int retryCount,
            String errorMessage,
            boolean terminal
    ) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<VideoAgentRun> update = new LambdaUpdateWrapper<VideoAgentRun>()
                .eq(VideoAgentRun::getId, runId)
                .notIn(VideoAgentRun::getStatus, TERMINAL)
                .set(VideoAgentRun::getStatus, status)
                .set(VideoAgentRun::getRetryCount, retryCount)
                .set(VideoAgentRun::getErrorMessage, concise(errorMessage))
                .set(VideoAgentRun::getUpdatedAt, now);
        if (terminal) {
            update.set(VideoAgentRun::getFinishedAt, now);
        }
        runMapper.update(null, update);
    }

    private void ensureWithinDeadline(String runId) {
        VideoAgentRun run = getRequired(runId);
        if (run.getDeadlineAt() != null && LocalDateTime.now().isAfter(run.getDeadlineAt())) {
            throw new IllegalStateException("Agent run exceeded its 10 minute deadline");
        }
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private String concise(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown agent failure";
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
