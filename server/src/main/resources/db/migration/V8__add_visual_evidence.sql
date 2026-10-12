CREATE TABLE media_visual_evidence (
    media_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    frame_count INT NOT NULL DEFAULT 0,
    error_message TEXT NULL,
    started_at DATETIME NOT NULL,
    finished_at DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (media_id),
    CONSTRAINT fk_media_visual_evidence_media
        FOREIGN KEY (media_id) REFERENCES media_files(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE video_keyframes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    media_id BIGINT NOT NULL,
    frame_index INT NOT NULL,
    timestamp_ms BIGINT NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    ocr_status VARCHAR(16) NOT NULL,
    ocr_text LONGTEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_keyframe_media_timestamp (media_id, timestamp_ms),
    INDEX idx_video_keyframe_media_index (media_id, frame_index),
    CONSTRAINT fk_video_keyframe_media
        FOREIGN KEY (media_id) REFERENCES media_files(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
