-- V18 inventory: aggregates and bounded ID-only candidate mappings.
-- No titles, transcripts, chat text, passwords, tokens, or object URLs.
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
SET SESSION TRANSACTION READ ONLY;
START TRANSACTION WITH CONSISTENT SNAPSHOT;

SELECT JSON_OBJECT('section', 'schema', 'data', JSON_OBJECT(
  'database', DATABASE(), 'mysql_version', VERSION(),
  'read_only', @@transaction_read_only,
  'latest_flyway_version', (SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1),
  'failed_migrations', (SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0)));

SELECT JSON_OBJECT('section', 'migration_history', 'data', JSON_OBJECT(
  'version', version, 'success', success, 'installed_rank', installed_rank))
FROM flyway_schema_history ORDER BY installed_rank;

SELECT JSON_OBJECT('section', 'counts', 'data', JSON_OBJECT(
  'users', (SELECT COUNT(*) FROM users),
  'media', (SELECT COUNT(*) FROM media_files),
  'conversations', (SELECT COUNT(*) FROM video_conversations),
  'messages', (SELECT COUNT(*) FROM video_messages),
  'runs', (SELECT COUNT(*) FROM video_agent_runs),
  'steps', (SELECT COUNT(*) FROM video_agent_steps),
  'analysis_jobs', (SELECT COUNT(*) FROM analysis_jobs),
  'transcript_segments', (SELECT COUNT(*) FROM transcript_segments),
  'knowledge_chunks', (SELECT COUNT(*) FROM video_knowledge_chunks)));

SELECT JSON_OBJECT('section', 'integrity', 'data', JSON_OBJECT(
  'media_without_owner', (SELECT COUNT(*) FROM media_files WHERE user_id IS NULL),
  'media_with_missing_user', (SELECT COUNT(*) FROM media_files m LEFT JOIN users u ON u.id = m.user_id WHERE m.user_id IS NOT NULL AND u.id IS NULL),
  'conversations_without_media', (SELECT COUNT(*) FROM video_conversations c LEFT JOIN media_files m ON m.id = c.media_id WHERE m.id IS NULL),
  'runs_without_media', (SELECT COUNT(*) FROM video_agent_runs r LEFT JOIN media_files m ON m.id = r.media_id WHERE m.id IS NULL),
  'messages_without_conversation', (SELECT COUNT(*) FROM video_messages x LEFT JOIN video_conversations c ON c.id = x.conversation_id WHERE c.id IS NULL),
  'messages_with_missing_run', (SELECT COUNT(*) FROM video_messages x LEFT JOIN video_agent_runs r ON r.id = x.agent_run_id WHERE x.agent_run_id IS NOT NULL AND r.id IS NULL),
  'steps_without_run', (SELECT COUNT(*) FROM video_agent_steps s LEFT JOIN video_agent_runs r ON r.id = s.run_id WHERE r.id IS NULL),
  'cross_media_run_links', (SELECT COUNT(*) FROM video_messages x JOIN video_conversations c ON c.id = x.conversation_id JOIN video_agent_runs r ON r.id = x.agent_run_id WHERE c.media_id <> r.media_id),
  'runs_without_timeline_link', (SELECT COUNT(*) FROM video_agent_runs r LEFT JOIN video_messages x ON x.agent_run_id = r.id WHERE x.id IS NULL),
  'runs_with_duplicate_timeline_links', (SELECT COUNT(*) FROM (SELECT agent_run_id FROM video_messages WHERE agent_run_id IS NOT NULL GROUP BY agent_run_id HAVING COUNT(*) > 1) duplicates)));

SELECT JSON_OBJECT('section', 'mapping_totals', 'data', JSON_OBJECT(
  'conversations_with_candidate_owner', (SELECT COUNT(*) FROM video_conversations c JOIN media_files m ON m.id = c.media_id JOIN users u ON u.id = m.user_id),
  'runs_with_candidate_owner', (SELECT COUNT(*) FROM video_agent_runs r JOIN media_files m ON m.id = r.media_id JOIN users u ON u.id = m.user_id),
  'candidate_only_not_verified_learner', TRUE));

SELECT JSON_OBJECT('section', 'conversation_mapping_preview', 'data', JSON_OBJECT(
  'conversation_id', c.id, 'media_id', c.media_id,
  'candidate_owner_id', u.id, 'candidate_only', TRUE))
FROM video_conversations c LEFT JOIN media_files m ON m.id = c.media_id
LEFT JOIN users u ON u.id = m.user_id ORDER BY c.id LIMIT 20;

SELECT JSON_OBJECT('section', 'run_mapping_preview', 'data', JSON_OBJECT(
  'run_id', r.id, 'media_id', r.media_id, 'conversation_id', x.conversation_id,
  'candidate_owner_id', u.id, 'candidate_only', TRUE))
FROM video_agent_runs r LEFT JOIN media_files m ON m.id = r.media_id
LEFT JOIN users u ON u.id = m.user_id
LEFT JOIN video_messages x ON x.agent_run_id = r.id ORDER BY r.id LIMIT 20;

SELECT JSON_OBJECT('section', 'run_status_counts', 'data', JSON_OBJECT('status', status, 'count', COUNT(*)))
FROM video_agent_runs GROUP BY status ORDER BY status;

SELECT JSON_OBJECT('section', 'active_summary', 'data', JSON_OBJECT(
  'active_runs', (SELECT COUNT(*) FROM video_agent_runs WHERE status NOT IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'NEEDS_INPUT')),
  'active_analysis_jobs', (SELECT COUNT(*) FROM analysis_jobs WHERE status NOT IN ('SUCCEEDED', 'FAILED'))));

SELECT JSON_OBJECT('section', 'active_run_preview', 'data', JSON_OBJECT(
  'run_id', id, 'status', status, 'retry_count', retry_count,
  'generation_calls', generation_calls, 'retrieval_calls', retrieval_calls,
  'deadline_at', deadline_at, 'next_attempt_at', next_attempt_at))
FROM video_agent_runs WHERE status NOT IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'NEEDS_INPUT') ORDER BY id LIMIT 20;

SELECT JSON_OBJECT('section', 'delete_rules', 'data', JSON_OBJECT(
  'table', TABLE_NAME, 'constraint', CONSTRAINT_NAME, 'delete_rule', DELETE_RULE))
FROM information_schema.REFERENTIAL_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME IN ('video_conversations', 'video_agent_runs', 'video_messages', 'video_agent_steps')
ORDER BY TABLE_NAME, CONSTRAINT_NAME;

ROLLBACK;
