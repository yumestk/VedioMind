CREATE TABLE video_chapters (
    id BIGINT NOT NULL AUTO_INCREMENT,
    media_id BIGINT NOT NULL,
    chapter_index INT NOT NULL,
    title VARCHAR(100) NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_chapter_media_index (media_id, chapter_index),
    INDEX idx_video_chapter_media_start (media_id, start_ms),
    CONSTRAINT fk_video_chapter_media
        FOREIGN KEY (media_id) REFERENCES media_files(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
