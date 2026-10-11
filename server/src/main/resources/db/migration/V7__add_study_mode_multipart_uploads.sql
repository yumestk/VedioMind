ALTER TABLE media_files
    ADD COLUMN analysis_mode VARCHAR(32) NOT NULL DEFAULT 'STUDY' AFTER source_type,
    ADD UNIQUE KEY uk_media_object_key (object_key(191));

UPDATE media_files
SET analysis_mode = 'QUICK_INSIGHT'
WHERE source_type = 'EXTERNAL_URL';

CREATE TABLE media_upload_sessions (
    id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    upload_id VARCHAR(500) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    filename VARCHAR(500) NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    part_size BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    media_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_upload_object_key (object_key),
    INDEX idx_media_upload_user_status (user_id, status),
    CONSTRAINT fk_media_upload_media
        FOREIGN KEY (media_id) REFERENCES media_files(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
