-- Non-empty pre-P1 databases require scripts/study-agent-p1-upgrade.sh first.
-- No automatic inference or startup data conversion.
ALTER TABLE video_conversations
 MODIFY learner_id BIGINT NOT NULL,
 ADD CONSTRAINT fk_conversation_learner FOREIGN KEY (learner_id) REFERENCES users(id),
 ADD CONSTRAINT fk_conversation_course FOREIGN KEY (course_id) REFERENCES courses(id),
 ADD UNIQUE KEY uk_conversation_identity (id, learner_id, media_id),
 ADD INDEX idx_conversation_learner_media (learner_id, media_id, course_id, updated_at);
ALTER TABLE video_agent_runs
 MODIFY learner_id BIGINT NOT NULL,
 MODIFY conversation_id BIGINT NOT NULL,
 MODIFY task_scope JSON NOT NULL,
 ADD CONSTRAINT fk_run_learning_conversation FOREIGN KEY (conversation_id, learner_id, media_id)
 REFERENCES video_conversations(id, learner_id, media_id),
 ADD CONSTRAINT fk_run_course FOREIGN KEY (course_id) REFERENCES courses(id),
 ADD COLUMN active_conversation_id BIGINT GENERATED ALWAYS AS
 (CASE WHEN status NOT IN ('SUCCEEDED','PARTIAL','FAILED') THEN conversation_id ELSE NULL END) STORED,
 ADD UNIQUE KEY uk_run_active_conversation (active_conversation_id),
 ADD UNIQUE KEY uk_run_conversation (id, conversation_id),
 ADD INDEX idx_run_learner_conversation (learner_id, conversation_id, created_at);

ALTER TABLE video_messages
 DROP FOREIGN KEY fk_video_message_agent_run,
 ADD CONSTRAINT fk_message_own_conversation_run FOREIGN KEY (agent_run_id, conversation_id)
 REFERENCES video_agent_runs(id, conversation_id) ON DELETE RESTRICT;
