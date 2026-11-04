package com.example.server.service;

import com.alibaba.fastjson2.JSON;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Map;

/** Append only while the caller holds the Run row lock in its business transaction. */
public class StudyRunEvents {
    private final JdbcTemplate jdbc;

    public StudyRunEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(
            String id, long epoch, String type, Map<String, ?> payload, LocalDateTime now) {
        jdbc.update("UPDATE video_agent_runs SET event_sequence=event_sequence+1 WHERE id=?", id);
        long sequence =
                jdbc.queryForObject(
                        "SELECT event_sequence FROM video_agent_runs WHERE id=?", Long.class, id);
        jdbc.update(
                "INSERT INTO"
                    + " study_run_events(run_id,sequence_no,execution_epoch,event_type,payload,created_at)"
                    + " VALUES (?,?,?,?,?,?)",
                id,
                sequence,
                epoch,
                type,
                JSON.toJSONString(payload),
                now);
    }
}
