-- No historical task/version is invented. Completed legacy runs retain their results; new work must be prepared.
ALTER TABLE video_agent_runs ADD COLUMN prepared_task JSON NULL;
ALTER TABLE video_messages ADD COLUMN prepared_task JSON NULL;
ALTER TABLE video_message_citations
 ADD COLUMN material_id BIGINT NULL,
 ADD COLUMN context_version VARCHAR(64) NULL,
 ADD COLUMN evidence_ref VARCHAR(200) NULL,
 ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'TRANSCRIPT';
-- Existing citations have an unambiguous source via their conversation. Historical source version remains unknown.
UPDATE video_message_citations c JOIN video_messages m ON m.id=c.message_id
 JOIN video_conversations v ON v.id=m.conversation_id SET c.material_id=v.media_id;
ALTER TABLE video_message_citations
 MODIFY material_id BIGINT NOT NULL,
 ADD CONSTRAINT fk_citation_preserved_source FOREIGN KEY (material_id) REFERENCES media_files(id) ON DELETE RESTRICT;
