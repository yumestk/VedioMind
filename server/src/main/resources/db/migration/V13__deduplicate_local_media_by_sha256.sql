ALTER TABLE media_files
    ADD COLUMN content_sha256 CHAR(64) NULL AFTER file_size,
    ADD UNIQUE KEY uk_media_user_content_sha256 (user_id, content_sha256);

ALTER TABLE media_upload_sessions
    ADD COLUMN content_sha256 CHAR(64) NULL AFTER part_size;
