ALTER TABLE analysis_jobs ADD COLUMN dispatch_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE analysis_jobs ADD COLUMN next_attempt_at DATETIME NULL;
ALTER TABLE analysis_jobs ADD COLUMN quota_wait_started_at DATETIME NULL;
CREATE INDEX idx_analysis_job_recovery ON analysis_jobs (status, next_attempt_at);

ALTER TABLE video_agent_runs ADD COLUMN dispatch_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE video_agent_runs ADD COLUMN next_attempt_at DATETIME NULL;
ALTER TABLE video_agent_runs ADD COLUMN quota_wait_started_at DATETIME NULL;
CREATE INDEX idx_agent_run_recovery ON video_agent_runs (status, next_attempt_at);
