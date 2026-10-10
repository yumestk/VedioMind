ALTER TABLE media_files
    ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'LOCAL_UPLOAD' AFTER status,
    ADD COLUMN source_platform VARCHAR(32) NULL AFTER source_type,
    ADD COLUMN source_url VARCHAR(1000) NULL AFTER source_platform,
    ADD COLUMN external_id VARCHAR(100) NULL AFTER source_url,
    ADD COLUMN duration_ms BIGINT NULL AFTER file_size,
    ADD COLUMN transcript_source VARCHAR(50) NULL AFTER duration_ms,
    ADD COLUMN transcript_language VARCHAR(50) NULL AFTER transcript_source;
