package com.example.server.service;

import java.util.Optional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.dto.AnalysisJobResponse;
import com.example.server.dto.VideoAgentCitation;
import com.example.server.entity.AnalysisJob;
import com.example.server.mapper.AnalysisJobMapper;
import com.example.server.service.context.VideoContextService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Durable index requests are dispatched by BackgroundTaskRecoveryService after commit. */
@Service
public class KnowledgeIndexPreparationService {
    private final AnalysisJobMapper jobs;
    private final JdbcTemplate jdbc;
    private final VideoContextService contexts;
    private final VideoQuestionService questions;

    public KnowledgeIndexPreparationService(AnalysisJobMapper jobs, JdbcTemplate jdbc,
                                             VideoContextService contexts, VideoQuestionService questions) {
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.questions = questions;
    }

    @Transactional
    public AnalysisJob prepare(Long mediaId, Long userId, boolean retry) {
        // Lock before establishing a repeatable-read snapshot, so concurrent submissions see the committed task.
        var rows = jdbc.queryForList("SELECT user_id FROM media_files WHERE id = ? AND deleted_at IS NULL FOR UPDATE", mediaId);
        if (rows.isEmpty() || userId == null || ((Number) rows.getFirst().get("user_id")).longValue() != userId) {
            throw new NoSuchElementException("Video does not exist or is not owned by this user");
        }
        String version = contexts.currentVersion(mediaId);
        var query = new LambdaQueryWrapper<AnalysisJob>().eq(AnalysisJob::getMediaId, mediaId)
                .eq(AnalysisJob::getJobType, "KNOWLEDGE_INDEX").eq(AnalysisJob::getContextVersion, version)
                .orderByDesc(AnalysisJob::getCreatedAt).last("LIMIT 1");
        AnalysisJob existing = jobs.selectOne(query);
        if (existing != null && !"SUCCEEDED".equals(existing.getStatus())
                && (!"FAILED".equals(existing.getStatus()) || !retry)) return existing;
        AnalysisJob job = new AnalysisJob();
        job.setId(UUID.randomUUID().toString());
        job.setMediaId(mediaId);
        job.setJobType("KNOWLEDGE_INDEX");
        job.setContextVersion(version);
        job.setStatus("QUEUED");
        job.setProgress(0);
        job.setRetryCount(0);
        job.setDispatchVersion(0L);
        job.setCreatedAt(LocalDateTime.now());
        // Publication runs after commit through the durable dispatcher.
        job.setUpdatedAt(LocalDateTime.now());
        jobs.insert(job);
        return job;
    }

    @Transactional
    public void prepareIfNeeded(Long mediaId, Long userId) {
        var context = contexts.build(mediaId);
        var candidates = context.transcriptEvidence().stream().map(value ->
                new VideoEvidenceContextService.Candidate(new VideoAgentCitation("TRANSCRIPT:" + value.id(),
                        "TRANSCRIPT", value.id(), value.startMs(), value.endMs(), value.text()), true, 0)).toList();
        if (!candidates.isEmpty() && !questions.fitsFull("问".repeat(500), candidates, List.of(), "")) {
            prepare(mediaId, userId, false);
        }
    }

    public Optional<AnalysisJob> latest(Long mediaId) {
        String version = contexts.currentVersion(mediaId);
        return Optional.ofNullable(jobs.selectOne(new LambdaQueryWrapper<AnalysisJob>()
                .eq(AnalysisJob::getMediaId, mediaId).eq(AnalysisJob::getJobType, "KNOWLEDGE_INDEX")
                .eq(AnalysisJob::getContextVersion, version).orderByDesc(AnalysisJob::getCreatedAt).last("LIMIT 1")));
    }

    public static class IndexPreparationException extends RuntimeException {
        private final AnalysisJob job;
        public IndexPreparationException(AnalysisJob job) {
            super("FAILED".equals(job.getStatus()) ? "视频问答资料准备失败，请重试" : "正在准备视频问答资料，请完成后重新发送");
            this.job = job;
        }
        public AnalysisJobResponse job() { return AnalysisJobResponse.from(job); }
    }
}
