ALTER TABLE video_keyframes
    ADD COLUMN ocr_confidence DECIMAL(5, 2) NULL AFTER ocr_status;

-- Existing READY sets contain unscored OCR and must be regenerated before reuse.
DELETE FROM media_visual_evidence;
