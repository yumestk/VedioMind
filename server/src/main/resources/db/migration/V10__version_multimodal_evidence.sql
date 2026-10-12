ALTER TABLE media_files
    ADD COLUMN transcript_revision INT NOT NULL DEFAULT 0 AFTER transcript_language;

UPDATE media_files media
SET transcript_revision = 1
WHERE EXISTS (
    SELECT 1
    FROM transcript_segments segment
    WHERE segment.media_id = media.id
);

ALTER TABLE media_visual_evidence
    ADD COLUMN processor_version INT NOT NULL DEFAULT 1 AFTER status,
    ADD COLUMN revision INT NOT NULL DEFAULT 0 AFTER processor_version,
    ADD COLUMN usable_ocr_count INT NOT NULL DEFAULT 0 AFTER frame_count,
    ADD COLUMN low_confidence_count INT NOT NULL DEFAULT 0 AFTER usable_ocr_count,
    ADD COLUMN empty_ocr_count INT NOT NULL DEFAULT 0 AFTER low_confidence_count,
    ADD COLUMN failed_ocr_count INT NOT NULL DEFAULT 0 AFTER empty_ocr_count;

ALTER TABLE video_keyframes
    ADD COLUMN processor_version INT NOT NULL DEFAULT 1 AFTER ocr_status;

ALTER TABLE analysis_jobs
    ADD COLUMN warning_message TEXT NULL AFTER error_message;
