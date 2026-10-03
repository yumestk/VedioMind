CREATE TABLE transcript_segments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    media_id BIGINT NOT NULL,
    segment_index INT NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    text TEXT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_transcript_segment_media_index (media_id, segment_index),
    INDEX idx_transcript_segment_media_start (media_id, start_ms),
    CONSTRAINT fk_transcript_segment_media
        FOREIGN KEY (media_id) REFERENCES media_files(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE media_files
    DROP COLUMN transcript_text;
