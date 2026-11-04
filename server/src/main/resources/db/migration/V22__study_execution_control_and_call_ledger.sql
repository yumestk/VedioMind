-- Old active work has no call ledger and cannot be safely replayed by the new runtime.
ALTER TABLE video_agent_runs
 ADD COLUMN request_id VARCHAR(36) NULL,
 ADD COLUMN execution_epoch BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN phase VARCHAR(40) NULL,
 ADD COLUMN stop_code VARCHAR(64) NULL,
 ADD COLUMN active_limit_ms BIGINT NOT NULL DEFAULT 120000,
 ADD COLUMN active_remaining_ms BIGINT NOT NULL DEFAULT 120000,
 ADD COLUMN active_started_at DATETIME(3) NULL,
 ADD COLUMN waiting_ms BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN wait_started_at DATETIME(3) NULL,
 ADD COLUMN event_sequence BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN accounted_tokens BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN unknown_calls INT NOT NULL DEFAULT 0;
UPDATE video_agent_runs SET status=CASE WHEN result_json IS NULL THEN 'FAILED' ELSE 'PARTIAL' END,
 stop_code='TASK_RUNTIME_CHANGED',error_message='执行控制已升级，请重新提交任务',
 next_attempt_at=NULL,finished_at=CURRENT_TIMESTAMP
 WHERE status NOT IN ('SUCCEEDED','PARTIAL','FAILED');
ALTER TABLE video_agent_runs DROP INDEX uk_run_active_conversation, DROP COLUMN active_conversation_id;
ALTER TABLE video_agent_runs ADD COLUMN active_conversation_id BIGINT GENERATED ALWAYS AS
 (CASE WHEN status NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','NEEDS_INPUT') THEN conversation_id ELSE NULL END) STORED,
 ADD UNIQUE KEY uk_run_active_conversation(active_conversation_id),
 ADD UNIQUE KEY uk_run_request(request_id);

CREATE TABLE study_ai_requests (
 id VARCHAR(36) PRIMARY KEY,
 learner_id BIGINT NULL,
 run_id VARCHAR(36) NULL UNIQUE,
 profile VARCHAR(32) NOT NULL,
 max_generation_calls INT NOT NULL,
 max_retrieval_calls INT NOT NULL,
 max_accounted_tokens BIGINT NOT NULL,
 generation_calls INT NOT NULL DEFAULT 0,
 retrieval_calls INT NOT NULL DEFAULT 0,
 accounted_tokens BIGINT NOT NULL DEFAULT 0,
 input_tokens BIGINT NOT NULL DEFAULT 0,
 output_tokens BIGINT NOT NULL DEFAULT 0,
 unknown_calls INT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL,
 deadline_at DATETIME(3) NOT NULL,
 INDEX idx_request_learner_created(learner_id,created_at),
 CONSTRAINT fk_request_learner FOREIGN KEY(learner_id) REFERENCES users(id),
 CONSTRAINT fk_request_run FOREIGN KEY(run_id) REFERENCES video_agent_runs(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_call_attempts (
 id VARCHAR(36) PRIMARY KEY,
 request_id VARCHAR(36) NOT NULL,
 run_id VARCHAR(36) NULL,
 learner_id BIGINT NULL,
 execution_epoch BIGINT NOT NULL,
 purpose VARCHAR(80) NOT NULL,
 provider VARCHAR(48) NOT NULL,
 kind VARCHAR(16) NOT NULL,
 fingerprint CHAR(64) NOT NULL,
 reserved_input BIGINT NOT NULL,
 reserved_output BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL,
 usage_status VARCHAR(16) NOT NULL,
 input_tokens BIGINT NULL,
 output_tokens BIGINT NULL,
 http_status INT NULL,
 response_json LONGTEXT NULL,
 error_code VARCHAR(64) NULL,
 elapsed_ms BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL,
 sent_at DATETIME(3) NULL,
 finished_at DATETIME(3) NULL,
 INDEX idx_attempt_replay(request_id,purpose,fingerprint,status),
 INDEX idx_attempt_recovery(run_id,status),
 INDEX idx_attempt_student(learner_id,created_at),
 CONSTRAINT fk_attempt_request FOREIGN KEY(request_id) REFERENCES study_ai_requests(id) ON DELETE RESTRICT,
 CONSTRAINT fk_attempt_run FOREIGN KEY(run_id) REFERENCES video_agent_runs(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE study_run_events (
 run_id VARCHAR(36) NOT NULL,
 sequence_no BIGINT NOT NULL,
 execution_epoch BIGINT NOT NULL,
 event_type VARCHAR(40) NOT NULL,
 payload JSON NOT NULL,
 created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(run_id,sequence_no),
 CONSTRAINT fk_event_run FOREIGN KEY(run_id) REFERENCES video_agent_runs(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
