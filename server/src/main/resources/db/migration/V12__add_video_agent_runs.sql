CREATE TABLE video_agent_runs (
    id VARCHAR(36) NOT NULL,
    media_id BIGINT NOT NULL,
    goal VARCHAR(500) NOT NULL,
    context_version VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    generation_calls INT NOT NULL DEFAULT 0,
    retrieval_calls INT NOT NULL DEFAULT 0,
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    token_usage_known BOOLEAN NOT NULL DEFAULT TRUE,
    result_json JSON NULL,
    citations_json JSON NULL,
    error_message VARCHAR(2000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    started_at DATETIME NULL,
    deadline_at DATETIME NULL,
    finished_at DATETIME NULL,
    PRIMARY KEY (id),
    INDEX idx_video_agent_run_media (media_id, created_at),
    INDEX idx_video_agent_run_status (status, updated_at),
    CONSTRAINT fk_video_agent_run_media
        FOREIGN KEY (media_id) REFERENCES media_files(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE video_agent_steps (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id VARCHAR(36) NOT NULL,
    stage VARCHAR(40) NOT NULL,
    round_no INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    input_json JSON NULL,
    output_json JSON NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_agent_step (run_id, stage, round_no),
    CONSTRAINT fk_video_agent_step_run
        FOREIGN KEY (run_id) REFERENCES video_agent_runs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
