ALTER TABLE media_files
    ADD COLUMN ai_summary_context_version VARCHAR(64) NULL AFTER ai_summary;

CREATE TABLE video_knowledge_chunks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    media_id BIGINT NOT NULL,
    context_version VARCHAR(64) NOT NULL,
    processor_version INT NOT NULL,
    chunk_index INT NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    summary TEXT NOT NULL,
    keywords_json JSON NOT NULL,
    embedding_json JSON NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_knowledge_chunk_version (
        media_id, context_version, processor_version, chunk_index
    ),
    INDEX idx_video_knowledge_chunk_ready (
        media_id, context_version, processor_version, status
    ),
    CONSTRAINT fk_video_knowledge_chunk_media
        FOREIGN KEY (media_id) REFERENCES media_files(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
