ALTER TABLE analysis_jobs ADD COLUMN job_type VARCHAR(32) NOT NULL DEFAULT 'ANALYSIS';
ALTER TABLE analysis_jobs ADD COLUMN context_version VARCHAR(255) NULL;
CREATE INDEX idx_analysis_job_kind_version ON analysis_jobs(media_id, job_type, context_version);
