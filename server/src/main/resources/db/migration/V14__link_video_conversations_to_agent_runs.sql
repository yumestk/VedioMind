ALTER TABLE video_conversations
    ADD COLUMN source_agent_run_id VARCHAR(36) NULL AFTER media_id,
    ADD INDEX idx_video_conversation_source_agent_run (source_agent_run_id),
    ADD CONSTRAINT fk_video_conversation_source_agent_run
        FOREIGN KEY (source_agent_run_id) REFERENCES video_agent_runs(id) ON DELETE SET NULL;
