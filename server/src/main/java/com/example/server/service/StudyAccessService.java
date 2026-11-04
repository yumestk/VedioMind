package com.example.server.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

/** Content access never grants access to another learner's private records. No Redis authorization cache. */
@Service
public class StudyAccessService {
    private final JdbcTemplate jdbc;

    public StudyAccessService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean canRead(Long mediaId, Long courseId, Long learnerId) {
        if (mediaId == null || learnerId == null) return false;
        if (courseId != null) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM course_materials cm JOIN courses c ON c.id=cm.course_id "
                    + "JOIN media_files m ON m.id=cm.media_id WHERE cm.media_id=? AND c.id=? "
                    + "AND m.deleted_at IS NULL AND (c.author_id=? OR c.status='PUBLISHED')",
                    Integer.class, mediaId, courseId, learnerId) > 0;
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM media_files m WHERE m.id=? AND m.deleted_at IS NULL "
                + "AND (m.user_id=? OR EXISTS (SELECT 1 FROM course_materials cm JOIN courses c ON c.id=cm.course_id "
                + "WHERE cm.media_id=m.id AND c.status='PUBLISHED'))", Integer.class, mediaId, learnerId) > 0;
    }

    public void requireRead(Long mediaId, Long courseId, Long learnerId) {
        if (!canRead(mediaId, courseId, learnerId)) throw new NoSuchElementException("Source is unavailable");
    }

    /** Transactional writes fence publication/removal/deletion until the write commits. */
    public void lockReadable(Long mediaId, Long courseId, Long learnerId) {
        if (courseId != null) {
            jdbc.queryForList("SELECT id FROM courses WHERE id=? FOR SHARE", Long.class, courseId);
        } else {
            jdbc.queryForList("SELECT c.id FROM courses c JOIN course_materials cm ON c.id=cm.course_id "
                    + "WHERE cm.media_id=? AND c.status='PUBLISHED' ORDER BY c.id FOR SHARE", Long.class, mediaId);
        }
        jdbc.queryForList("SELECT id FROM media_files WHERE id=? FOR SHARE", Long.class, mediaId);
        requireRead(mediaId, courseId, learnerId);
    }

    public void requireAuthor(Long mediaId, Long authorId) {
        if (authorId == null || jdbc.queryForObject("SELECT COUNT(*) FROM media_files WHERE id=? AND user_id=? "
                + "AND deleted_at IS NULL", Integer.class, mediaId, authorId) != 1) {
            throw new NoSuchElementException("Media not found");
        }
    }

    public void requireLearner(Long actual, Long requested) {
        if (requested == null || !requested.equals(actual)) throw new NoSuchElementException("Study record not found");
    }

    @Transactional
    public void deleteSource(Long mediaId, Long userId) {
        if (userId == null || jdbc.queryForObject("SELECT COUNT(*) FROM media_files WHERE id=? AND user_id=?",
                Integer.class, mediaId, userId) != 1) throw new NoSuchElementException("Media not found");
        jdbc.update("UPDATE media_files SET deleted_at=COALESCE(deleted_at,CURRENT_TIMESTAMP),content_sha256=NULL WHERE id=?", mediaId);
        jdbc.update("UPDATE analysis_jobs SET status='FAILED',error_message='来源已删除',next_attempt_at=NULL,"
                + "finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE media_id=? AND status NOT IN ('SUCCEEDED','FAILED')", mediaId);
        stopUnavailableRuns();
    }

    /** Called in the same transaction as a withdrawal/removal/deletion. Fences late result commits. */
    public void stopUnavailableRuns() {
        // ponytail: short global active-run lock; select affected runs first if shared-course traffic grows.
        var candidates=jdbc.queryForList("SELECT id FROM video_agent_runs WHERE status NOT IN "+com.example.server.entity.StudyRunStatus.TERMINAL_SQL+" ORDER BY id FOR UPDATE",String.class);
        jdbc.update("UPDATE video_agent_runs r JOIN media_files m ON m.id=r.media_id SET "
                + "r.status=CASE WHEN r.result_json IS NULL THEN 'FAILED' ELSE 'PARTIAL' END, "
                + "r.error_message='来源已撤回或删除，任务已停止', r.stop_code='TASK_SOURCE_UNAVAILABLE',r.execution_epoch=r.execution_epoch+1,r.dispatch_version=r.dispatch_version+1, r.next_attempt_at=NULL, "
                + "r.active_remaining_ms=GREATEST(0,r.active_remaining_ms-COALESCE(TIMESTAMPDIFF(MICROSECOND,r.active_started_at,CURRENT_TIMESTAMP(3))/1000,0)),r.active_started_at=NULL, "
                + "r.waiting_ms=r.waiting_ms+COALESCE(TIMESTAMPDIFF(MICROSECOND,r.wait_started_at,CURRENT_TIMESTAMP(3))/1000,0),r.wait_started_at=NULL, "
                + "r.finished_at=CURRENT_TIMESTAMP, r.updated_at=CURRENT_TIMESTAMP "
                + "WHERE r.status NOT IN "+com.example.server.entity.StudyRunStatus.TERMINAL_SQL+" AND (m.deleted_at IS NOT NULL OR "
                + "(r.course_id IS NULL AND m.user_id<>r.learner_id AND NOT EXISTS "
                + "(SELECT 1 FROM course_materials cm JOIN courses c ON c.id=cm.course_id "
                + "WHERE cm.media_id=r.media_id AND c.status='PUBLISHED')) OR "
                + "(r.course_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM course_materials cm JOIN courses c "
                + "ON c.id=cm.course_id WHERE c.id=r.course_id AND cm.media_id=r.media_id "
                + "AND (c.author_id=r.learner_id OR c.status='PUBLISHED'))))");
        for(String id:candidates) {
            var r=jdbc.queryForMap("SELECT execution_epoch,stop_code,status FROM video_agent_runs WHERE id=?",id);
            if("TASK_SOURCE_UNAVAILABLE".equals(r.get("stop_code")))new StudyRunEvents(jdbc).append(id,((Number)r.get("execution_epoch")).longValue(),"TASK_STOPPED",
                    java.util.Map.of("status",r.get("status"),"code","TASK_SOURCE_UNAVAILABLE"),java.time.LocalDateTime.now());
        }
    }
}
