package com.example.server.service;

import com.example.server.entity.*;
import com.example.server.mapper.*;
import com.example.server.service.context.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class KnowledgeIndexPreparationServiceTest {
    final AnalysisJobMapper jobs = mock(AnalysisJobMapper.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final VideoContextService contexts = mock(VideoContextService.class);
    final VideoQuestionService questions = mock(VideoQuestionService.class);
    final KnowledgeIndexPreparationService service = new KnowledgeIndexPreparationService(jobs, jdbc, contexts, questions);

    void owned() {
        when(jdbc.queryForList(anyString(), eq(37L))).thenReturn(List.of(java.util.Map.of("user_id", 1L)));
        when(contexts.currentVersion(37L)).thenReturn("t1-v0");
        when(contexts.build(37L)).thenReturn(new VideoContext(37L, "t1-v0", "BILIBILI", null, null,
                List.of(new VideoContext.TranscriptEvidence(1L, 0, 1000, "字幕")), List.of()));
    }

    @Test void activeTaskIsReusedAndFailureNeedsExplicitRetry() {
        owned();
        var existing = new AnalysisJob(); existing.setId("existing"); existing.setStatus("WAITING_FOR_QUOTA");
        when(jobs.selectOne(any())).thenReturn(existing);
        assertThat(service.prepare(37L, 1L, false)).isSameAs(existing);
        existing.setStatus("FAILED");
        assertThat(service.prepare(37L, 1L, false)).isSameAs(existing);
        verify(jobs, never()).insert(any(AnalysisJob.class));
        var retry = service.prepare(37L, 1L, true);
        assertThat(retry.getContextVersion()).isEqualTo("t1-v0");
        assertThat(retry.getJobType()).isEqualTo("KNOWLEDGE_INDEX");
        assertThat(retry.getRetryCount()).isZero();
        assertThat(retry.getStatus()).isEqualTo("QUEUED");
        verify(jobs).insert(retry);
    }

    @Test void prewarmsOnlyWhenFullTranscriptDoesNotFit() {
        owned();
        when(questions.fitsFull(anyString(), anyList(), anyList(), anyString())).thenReturn(true);
        service.prepareIfNeeded(37L, 1L);
        verify(jobs, never()).insert(any(AnalysisJob.class));
        when(questions.fitsFull(anyString(), anyList(), anyList(), anyString())).thenReturn(false);
        service.prepareIfNeeded(37L, 1L);
        verify(jobs).insert(any(AnalysisJob.class));
    }

    @Test void refusesAnotherUsersMediaBeforeQueueingPaidWork() {
        owned();
        assertThatThrownBy(() -> service.prepare(37L, 2L, true)).isInstanceOf(java.util.NoSuchElementException.class);
        verifyNoInteractions(jobs, questions);
    }
}
