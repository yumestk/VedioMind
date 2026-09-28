CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(100),
    avatar VARCHAR(500),
    role VARCHAR(50) DEFAULT 'USER',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS media_files (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT,
    filename VARCHAR(500),
    status VARCHAR(50) DEFAULT 'UPLOADED',
    file_path VARCHAR(1000),
    ai_summary TEXT,
    transcript_text LONGTEXT,
    cover_url VARCHAR(1000),
    upload_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_media_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE analysis_jobs (
    id VARCHAR(36) NOT NULL,
    media_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    started_at DATETIME,
    finished_at DATETIME,
    PRIMARY KEY (id),
    INDEX idx_analysis_job_media_created (media_id, created_at),
    INDEX idx_analysis_job_status (status),
    CONSTRAINT fk_analysis_job_media
        FOREIGN KEY (media_id) REFERENCES media_files(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
