ALTER TABLE media_files
    ADD COLUMN object_key VARCHAR(1000) NULL AFTER status,
    ADD COLUMN mime_type VARCHAR(255) NULL AFTER object_key,
    ADD COLUMN file_size BIGINT NULL AFTER mime_type;

UPDATE media_files
SET object_key = CASE
    WHEN file_path LIKE 'http://%' OR file_path LIKE 'https://%'
        THEN SUBSTRING_INDEX(file_path, '/', -1)
    ELSE file_path
END;

ALTER TABLE media_files
    DROP COLUMN file_path;
