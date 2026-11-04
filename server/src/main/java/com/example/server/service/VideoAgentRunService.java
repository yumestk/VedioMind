package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.dto.VideoAgentCitation;
import com.example.server.dto.VideoAgentResult;
import com.example.server.dto.VideoAgentRunMessage;
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

    public static final int MAX_GENERATION_CALLS = 3;
    public static final int MAX_RETRIEVAL_CALLS = 6;
    private static final List<String> TERMINAL = List.of("SUCCEEDED", "PARTIAL", "FAILED");

    private final VideoAgentRunMapper runMapper;
    private final VideoAgentStepMapper stepMapper;
    private final MediaFileMapper mediaFileMapper;
    private final VideoConversationStore conversationStore;
    private final StudyAccessService access;

    public VideoAgentRunService(
            VideoAgentRunMapper runMapper,
            VideoAgentStepMapper stepMapper,
            MediaFileMapper mediaFileMapper,
            VideoConversationStore conversationStore,
            StudyAccessService access
    ) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.mediaFileMapper = mediaFileMapper;
        this.conversationStore = conversationStore;
        this.access = access;
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void createQueuedRun(VideoAgentRunMessage message) {
        access.lockReadable(message.mediaId(), message.courseId(), message.learnerId());
        if (runMapper.selectById(message.runId()) != null) {
            return;
        }
        if (mediaFileMapper.selectById(message.mediaId()) == null) {
            throw new IllegalArgumentException("Media does not exist: " + message.mediaId());
        }
        var conversation = conversationStore.getRequired(message.conversationId());
        access.requireLearner(conversation.getLearnerId(), message.learnerId());
        access.requireRead(message.mediaId(), conversation.getCourseId(), message.learnerId());
        if (!java.util.Objects.equals(message.courseId(), conversation.getCourseId()))
            throw new IllegalArgumentException("Course context mismatch");
        if (findLatestActive(conversation.getId(), message.learnerId()).isPresent())
            throw new IllegalArgumentException("Conversation already has an active Agent task");
        if (!message.mediaId().equals(conversation.getMediaId())) {
            throw new IllegalArgumentException("Conversation does not belong to the Agent media");
        }
        LocalDateTime now = LocalDateTime.now();
        VideoAgentRun run = new VideoAgentRun();
        run.setId(message.runId());
        run.setMediaId(message.mediaId());
        run.setLearnerId(message.learnerId());
        run.setCourseId(conversation.getCourseId());
        run.setConversationId(conversation.getId());
        run.setTaskScope(JSON.toJSONString(java.util.Map.of("type", "VIDEO", "mediaIds", List.of(message.mediaId()))));
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
        conversationStore.appendAgentRunRequest(
                message.conversationId(), message.runId(), message.goal(), now
        );
    }

    public Optional<VideoAgentRun> findById(String runId) {
        return Optional.ofNullable(runMapper.selectById(runId));
    }

    public VideoAgentRun getRequired(String runId) {
        return findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Agent run does not exist: " + runId));
    }

    public Optional<VideoAgentRun> findLatestActive(Long conversationId, Long learnerId) {
        return Optional.ofNullable(runMapper.selectOne(
                new LambdaQueryWrapper<VideoAgentRun>()
                        .eq(VideoAgentRun::getConversationId, conversationId)
                        .eq(VideoAgentRun::getLearnerId, learnerId)
                        .notIn(VideoAgentRun::getStatus, TERMINAL)
                        .orderByDesc(VideoAgentRun::getCreatedAt)
                        .last("LIMIT 1")
        ));
    }

    public boolean beginAttempt(String runId, int retryCount) {
        VideoAgentRun run = getRequired(runId);
        if (TERMINAL.contains(run.getStatus())) {
            return false;
        }
        requireSource(run);
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<VideoAgentRun> update = new LambdaUpdateWrapper<VideoAgentRun>()
                .eq(VideoAgentRun::getId, runId)
                .notIn(VideoAgentRun::getStatus, TERMINAL)
                .set(VideoAgentRun::getRetryCount, retryCount)
                .set(VideoAgentRun::getErrorMessage, null)
                .set(VideoAgentRun::getUpdatedAt, now);
        if (run.getStartedAt() == null) {
            update.set(VideoAgentRun::getStartedAt, now)
                    .set(VideoAgentRun::getDeadlineAt, now.plusSeconds(120));
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
            throw new com.example.server.service.ai.AiPromptBudget.PromptBudgetExceededException("Agent generation call budget exhausted");
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
            throw new com.example.server.service.ai.AiPromptBudget.PromptBudgetExceededException("Agent retrieval call budget exhausted");
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

    public java.time.Instant requestDeadline(String runId) {
        ensureWithinDeadline(runId);
        // Leave time for validation and persistence rather than letting HTTP consume all 120 seconds.
        return getRequired(runId).getDeadlineAt().minusSeconds(2)
                .atZone(java.time.ZoneId.systemDefault()).toInstant();
    }

    public void saveUsableResult(String runId, VideoAgentResult result, List<VideoAgentCitation> citations) {
        ensureWithinDeadline(runId);
        runMapper.update(null, new LambdaUpdateWrapper<VideoAgentRun>()
                .eq(VideoAgentRun::getId, runId).notIn(VideoAgentRun::getStatus, TERMINAL)
                .set(VideoAgentRun::getResultJson, JSON.toJSONString(result))
                .set(VideoAgentRun::getCitationsJson, JSON.toJSONString(citations))
                .set(VideoAgentRun::getUpdatedAt, LocalDateTime.now()));
    }

    public void finishStopped(String runId, String reason) {
        VideoAgentRun run = getRequired(runId);
        LocalDateTime now = LocalDateTime.now();
        runMapper.update(null, new LambdaUpdateWrapper<VideoAgentRun>()
                .eq(VideoAgentRun::getId, runId).notIn(VideoAgentRun::getStatus, TERMINAL)
                .set(VideoAgentRun::getStatus, run.getResultJson() == null ? "FAILED" : "PARTIAL")
                .set(VideoAgentRun::getErrorMessage, reason == null ? "Task stopped" : reason.substring(0, Math.min(2000, reason.length())))
                .set(VideoAgentRun::getProgress, 100).set(VideoAgentRun::getNextAttemptAt, null)
                .set(VideoAgentRun::getFinishedAt, now).set(VideoAgentRun::getUpdatedAt, now));
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

    private void ensureWithinDeadline(String runId) {
        VideoAgentRun run = getRequired(runId);
        requireSource(run);
        if (TERMINAL.contains(run.getStatus())) throw new IllegalArgumentException("Agent task is already stopped");
        if (run.getDeadlineAt() != null && LocalDateTime.now().isAfter(run.getDeadlineAt())) {
            throw new com.example.server.service.ai.AiPromptBudget.PromptBudgetExceededException("Agent run exceeded its 120 second deadline");
        }
    }

    public VideoAgentRun getOwned(String runId, Long learnerId) {
        VideoAgentRun run = getRequired(runId);
        access.requireLearner(run.getLearnerId(), learnerId);
        return run;
    }

    private void requireSource(VideoAgentRun run) {
        try { access.requireRead(run.getMediaId(), run.getCourseId(), run.getLearnerId()); }
        catch (NoSuchElementException denied) {
            finishStopped(run.getId(), "来源已撤回或删除，任务已停止");
            throw new IllegalArgumentException("Source is unavailable");
        }
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

}
