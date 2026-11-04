-- Structure only. Existing learning rows must be explicitly converted offline before V20.
CREATE TABLE courses (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 author_id BIGINT NOT NULL,
 title VARCHAR(100) NOT NULL,
 description VARCHAR(2000) NOT NULL DEFAULT '',
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 structure_version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT fk_course_author FOREIGN KEY (author_id) REFERENCES users(id),
 INDEX idx_course_visibility (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE course_sections (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 course_id BIGINT NOT NULL,
 title VARCHAR(100) NOT NULL,
 position INT NOT NULL,
 UNIQUE KEY uk_section_course_id (course_id, id),
 CONSTRAINT fk_section_course FOREIGN KEY (course_id) REFERENCES courses(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE course_materials (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 course_id BIGINT NOT NULL,
 section_id BIGINT NOT NULL,
 media_id BIGINT NOT NULL,
 position INT NOT NULL,
 UNIQUE KEY uk_course_media (course_id, media_id),
 CONSTRAINT fk_material_section FOREIGN KEY (course_id, section_id) REFERENCES course_sections(course_id, id),
 CONSTRAINT fk_material_media FOREIGN KEY (media_id) REFERENCES media_files(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
ALTER TABLE media_files ADD COLUMN deleted_at DATETIME NULL;
ALTER TABLE video_conversations
 ADD COLUMN learner_id BIGINT NULL,
 ADD COLUMN course_id BIGINT NULL,
 DROP FOREIGN KEY fk_video_conversation_media,
 ADD CONSTRAINT fk_conversation_preserved_source FOREIGN KEY (media_id) REFERENCES media_files(id) ON DELETE RESTRICT;
ALTER TABLE video_agent_runs
 ADD COLUMN learner_id BIGINT NULL,
 ADD COLUMN conversation_id BIGINT NULL,
 ADD COLUMN course_id BIGINT NULL,
 ADD COLUMN task_scope JSON NULL,
 DROP FOREIGN KEY fk_video_agent_run_media,
 ADD CONSTRAINT fk_run_preserved_source FOREIGN KEY (media_id) REFERENCES media_files(id) ON DELETE RESTRICT;
