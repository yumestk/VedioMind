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
import java.util.OptionalLong;
import com.example.server.entity.StudyRunStatus;
import com.example.server.service.ai.AiCallLedger;
import com.example.server.service.ai.StudyExecutionContext;

@Service
public class VideoAgentRunService {

    public static final int MAX_GENERATION_CALLS = 3;
    public static final int MAX_RETRIEVAL_CALLS = 6;
    private static final List<String> TERMINAL = StudyRunStatus.TERMINAL;

    private final VideoAgentRunMapper runMapper;
    private final VideoAgentStepMapper stepMapper;
    private final MediaFileMapper mediaFileMapper;
    private final VideoConversationStore conversationStore;
    private final StudyAccessService access;
    private final StudyTaskPreparationService tasks;
    private final StudyRunControl control;
    private final AiCallLedger ledger;

    public VideoAgentRunService(
            VideoAgentRunMapper runMapper,
            VideoAgentStepMapper stepMapper,
            MediaFileMapper mediaFileMapper,
            VideoConversationStore conversationStore,
            StudyAccessService access, StudyTaskPreparationService tasks, StudyRunControl control, AiCallLedger ledger
    ) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.mediaFileMapper = mediaFileMapper;
        this.conversationStore = conversationStore;
        this.access = access; this.tasks = tasks; this.control=control;this.ledger=ledger;
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
        var task = message.preparedTask();
        if (task == null || !message.learnerId().equals(task.learnerId())
                || !message.conversationId().equals(task.conversationId())
                || !message.mediaId().equals(task.anchorMediaId()) || !message.goal().equals(task.goal())
                || !java.util.Objects.equals(message.courseId(), task.courseId())
                || !"VIDEO".equals(task.scope().type()) || task.materials().size() != 1
                || !message.contextVersion().equals(task.materials().getFirst().version()))
            throw new IllegalArgumentException("Prepared task does not match command");
        ledger.ensureRequest(task);
        tasks.lockCurrent(task);
        LocalDateTime now = LocalDateTime.now();
        VideoAgentRun run = new VideoAgentRun();
        run.setId(message.runId());
        run.setMediaId(message.mediaId());
        run.setLearnerId(message.learnerId());
        run.setCourseId(conversation.getCourseId());
        run.setConversationId(conversation.getId());
        run.setTaskScope(JSON.toJSONString(task.scope()));
        run.setPreparedTask(JSON.toJSONString(task));
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
        ledger.attach(task,run.getId());
        control.eventLocked(run.getId(),0,"TASK_QUEUED",java.util.Map.of("status","QUEUED"));
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

    public OptionalLong beginAttempt(String runId,long dispatchVersion) { return control.begin(runId,dispatchVersion); }

    public void markStage(String runId,String phase,int progress) {control.stage(runId,phase,progress);}

    public void checkExecution(String runId) {control.assertLease(runId);}

    public Optional<String> completedStep(String runId, String stage, int round) {
        control.assertLease(runId);
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
        lockTask(runId);
        control.lockLease(runId);
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
        control.eventLocked(runId,StudyExecutionContext.epoch(runId),"CHECKPOINT_SAVED",java.util.Map.of("stage",stage,"round",round));
    }

    public java.time.Instant requestDeadline(String runId) {return control.deadline(runId);}

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void saveUsableResult(String runId, VideoAgentResult result, List<VideoAgentCitation> citations) {
        lockTask(runId);
        control.lockLease(runId);
        runMapper.update(null, new LambdaUpdateWrapper<VideoAgentRun>()
                .eq(VideoAgentRun::getId, runId).eq(VideoAgentRun::getExecutionEpoch,StudyExecutionContext.epoch(runId)).notIn(VideoAgentRun::getStatus, TERMINAL)
                .set(VideoAgentRun::getResultJson, JSON.toJSONString(result))
                .set(VideoAgentRun::getCitationsJson, JSON.toJSONString(citations))
                .set(VideoAgentRun::getUpdatedAt, LocalDateTime.now()));
        control.eventLocked(runId,StudyExecutionContext.epoch(runId),"RESULT_SAVED",java.util.Map.of("partial",true));
    }

    public void finishStopped(String runId,String reason) {finishStopped(runId,"TASK_STOPPED",reason);}
    public void finishStopped(String runId,String code,String reason) {control.stop(runId,code,reason,null,true);}
    public void needsInput(String runId,String reason) {control.stop(runId,"TASK_NEEDS_INPUT",reason,"NEEDS_INPUT",true);}
    public void cancel(String runId,Long learnerId) {control.cancel(runId,learnerId);ledger.cancelHttp(runId);}
    public java.util.List<java.util.Map<String,Object>> events(String runId,Long learnerId,long after) {return control.events(runId,learnerId,after);}

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void markSucceeded(
            String runId,
            VideoAgentResult result,
            List<VideoAgentCitation> citations
    ) {
        lockTask(runId);
        var locked=control.lockLease(runId);
        runMapper.update(null,new LambdaUpdateWrapper<VideoAgentRun>().eq(VideoAgentRun::getId,runId)
                .eq(VideoAgentRun::getExecutionEpoch,StudyExecutionContext.epoch(runId))
                .set(VideoAgentRun::getResultJson,JSON.toJSONString(result))
                .set(VideoAgentRun::getCitationsJson,JSON.toJSONString(citations)));
        control.completeLocked(locked);
    }

    public VideoAgentRun getOwned(String runId, Long learnerId) {
        VideoAgentRun run = getRequired(runId);
        access.requireLearner(run.getLearnerId(), learnerId);
        return run;
    }

    public boolean sourceAvailable(VideoAgentRun run) {
        return tasks.sourceAvailable(run.getMediaId(), run.getCourseId(), run.getLearnerId(), run.getContextVersion());
    }

    private void lockTask(String runId) {
        var run = getRequired(runId);
        if (run.getPreparedTask() == null) throw new IllegalArgumentException("Task snapshot missing");
        tasks.lockCurrent(JSON.parseObject(run.getPreparedTask(), com.example.server.dto.PreparedStudyTask.class));
    }

}
