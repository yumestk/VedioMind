package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.dto.AnalysisJobMessage;
import com.example.server.entity.AnalysisJob;
import com.example.server.entity.AnalysisJobStatus;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.AnalysisJobMapper;
import com.example.server.mapper.MediaFileMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class AnalysisJobService {

    private static final List<String> TERMINAL_STATUSES = List.of(
            AnalysisJobStatus.SUCCEEDED.name(),
            AnalysisJobStatus.FAILED.name()
    );

    private final AnalysisJobMapper analysisJobMapper;
    private final MediaFileMapper mediaFileMapper;

    public AnalysisJobService(AnalysisJobMapper analysisJobMapper, MediaFileMapper mediaFileMapper) {
        this.analysisJobMapper = analysisJobMapper;
        this.mediaFileMapper = mediaFileMapper;
    }

    @Transactional
    public void createQueuedJob(AnalysisJobMessage message) {
        if (analysisJobMapper.selectById(message.jobId()) != null) {
            return;
        }

        MediaFile media = mediaFileMapper.selectById(message.mediaId());
        if (media == null) {
            throw new IllegalArgumentException("Media does not exist: " + message.mediaId());
        }

        LocalDateTime now = LocalDateTime.now();
        AnalysisJob job = new AnalysisJob();
        job.setId(message.jobId());
        job.setMediaId(message.mediaId());
        job.setStatus(AnalysisJobStatus.QUEUED.name());
        job.setProgress(0);
        job.setRetryCount(0);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        analysisJobMapper.insert(job);
    }

    public Optional<AnalysisJob> findById(String jobId) {
        return Optional.ofNullable(analysisJobMapper.selectById(jobId));
    }

    public AnalysisJob getRequired(String jobId) {
        return findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Analysis job does not exist: " + jobId));
    }

    public Optional<AnalysisJob> findLatestActive(Long mediaId) {
        LambdaQueryWrapper<AnalysisJob> query = new LambdaQueryWrapper<AnalysisJob>()
                .eq(AnalysisJob::getMediaId, mediaId)
                .notIn(AnalysisJob::getStatus, TERMINAL_STATUSES)
                .orderByDesc(AnalysisJob::getCreatedAt)
                .last("LIMIT 1");
        return Optional.ofNullable(analysisJobMapper.selectOne(query));
    }

    public boolean beginAttempt(String jobId, int retryCount) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<AnalysisJob> update = new LambdaUpdateWrapper<AnalysisJob>()
                .eq(AnalysisJob::getId, jobId)
                .notIn(AnalysisJob::getStatus, TERMINAL_STATUSES)
                .set(AnalysisJob::getStatus, AnalysisJobStatus.EXTRACTING_AUDIO.name())
                .set(AnalysisJob::getProgress, 10)
                .set(AnalysisJob::getRetryCount, retryCount)
                .set(AnalysisJob::getErrorMessage, null)
                .set(AnalysisJob::getStartedAt, now)
                .set(AnalysisJob::getUpdatedAt, now);
        return analysisJobMapper.update(null, update) == 1;
    }

    public void markStage(String jobId, AnalysisJobStatus status, int progress) {
        if (status.isTerminal()) {
            throw new IllegalArgumentException("Use a terminal state method for " + status);
        }
        LambdaUpdateWrapper<AnalysisJob> update = new LambdaUpdateWrapper<AnalysisJob>()
                .eq(AnalysisJob::getId, jobId)
                .notIn(AnalysisJob::getStatus, TERMINAL_STATUSES)
                .set(AnalysisJob::getStatus, status.name())
                .set(AnalysisJob::getProgress, progress)
                .set(AnalysisJob::getUpdatedAt, LocalDateTime.now());
        analysisJobMapper.update(null, update);
    }

    public void markRetrying(String jobId, int retryCount, String errorMessage) {
        LambdaUpdateWrapper<AnalysisJob> update = new LambdaUpdateWrapper<AnalysisJob>()
                .eq(AnalysisJob::getId, jobId)
                .notIn(AnalysisJob::getStatus, TERMINAL_STATUSES)
                .set(AnalysisJob::getStatus, AnalysisJobStatus.RETRYING.name())
                .set(AnalysisJob::getProgress, 5)
                .set(AnalysisJob::getRetryCount, retryCount)
                .set(AnalysisJob::getErrorMessage, errorMessage)
                .set(AnalysisJob::getUpdatedAt, LocalDateTime.now());
        analysisJobMapper.update(null, update);
    }

    public void markSucceeded(String jobId) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<AnalysisJob> update = new LambdaUpdateWrapper<AnalysisJob>()
                .eq(AnalysisJob::getId, jobId)
                .notIn(AnalysisJob::getStatus, TERMINAL_STATUSES)
                .set(AnalysisJob::getStatus, AnalysisJobStatus.SUCCEEDED.name())
                .set(AnalysisJob::getProgress, 100)
                .set(AnalysisJob::getErrorMessage, null)
                .set(AnalysisJob::getFinishedAt, now)
                .set(AnalysisJob::getUpdatedAt, now);
        analysisJobMapper.update(null, update);
    }

    public void markFailed(String jobId, int retryCount, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<AnalysisJob> update = new LambdaUpdateWrapper<AnalysisJob>()
                .eq(AnalysisJob::getId, jobId)
                .notIn(AnalysisJob::getStatus, TERMINAL_STATUSES)
                .set(AnalysisJob::getStatus, AnalysisJobStatus.FAILED.name())
                .set(AnalysisJob::getRetryCount, retryCount)
                .set(AnalysisJob::getErrorMessage, errorMessage)
                .set(AnalysisJob::getFinishedAt, now)
                .set(AnalysisJob::getUpdatedAt, now);
        analysisJobMapper.update(null, update);
    }
}
