ALTER TABLE video_conversations
    DROP FOREIGN KEY fk_video_conversation_source_agent_run,
    DROP INDEX idx_video_conversation_source_agent_run,
    DROP COLUMN source_agent_run_id;

ALTER TABLE video_messages
    CHANGE COLUMN role message_type VARCHAR(24) NOT NULL,
    ADD COLUMN agent_run_id VARCHAR(36) NULL AFTER content,
    ADD UNIQUE KEY uk_video_message_agent_run (agent_run_id),
    ADD CONSTRAINT fk_video_message_agent_run
        FOREIGN KEY (agent_run_id) REFERENCES video_agent_runs(id) ON DELETE CASCADE;
