#!/usr/bin/env python3
"""Check the inventory against synthetic MySQL data in a disposable database."""
import json
import os
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
CONTAINER = os.environ.get("P0_MYSQL_CONTAINER", "mysql-media")
DATABASE = "vediomind_p0_test_" + uuid.uuid4().hex
MARKER = "P0_PRIVATE_SENTINEL"


def mysql(sql, database=None, check=True):
    args = ["--database=" + database] if database else []
    return subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "sh", "-c",
         'MYSQL_PWD="${MYSQL_ROOT_PASSWORD:?Container credential required}"; '
         'export MYSQL_PWD; exec mysql --user=root --batch --raw --skip-column-names "$@"',
         "sh", *args], input=sql, text=True, capture_output=True, check=check,
    )


def main():
    created = False
    try:
        mysql("CREATE DATABASE `" + DATABASE + "` CHARACTER SET utf8mb4;")
        created = True
        migrations = sorted(
            (p for p in (ROOT / "server/src/main/resources/db/migration").glob("V*.sql")
             if int(p.name.split("__", 1)[0][1:]) <= 18),
            key=lambda p: int(p.name.split("__", 1)[0][1:]),
        )
        mysql("\n".join(p.read_text() for p in migrations), DATABASE)
        # This fixture validates audit queries, not Flyway checksum/history behavior.
        mysql("""
CREATE TABLE flyway_schema_history (installed_rank INT, version VARCHAR(50), success BOOLEAN);
INSERT INTO flyway_schema_history VALUES (18, '18', 1);
INSERT INTO users (id, username, password) VALUES
 (101, 'p0-author', 'synthetic-only'), (102, 'p0-student-b', 'synthetic-only'),
 (103, 'p0-student-c', 'synthetic-only');
INSERT INTO media_files (id, user_id, filename) VALUES
 (11, 101, 'P0_PRIVATE_SENTINEL'), (12, NULL, 'P0_PRIVATE_SENTINEL'),
 (13, 999, 'P0_PRIVATE_SENTINEL'), (14, 101, 'P0_PRIVATE_SENTINEL');
SET FOREIGN_KEY_CHECKS = 0;
INSERT INTO video_conversations (id, media_id, title, created_at, updated_at) VALUES
 (21, 11, 'P0_PRIVATE_SENTINEL', NOW(), NOW()),
 (22, 14, 'P0_PRIVATE_SENTINEL', NOW(), NOW()),
 (23, 999, 'P0_PRIVATE_SENTINEL', NOW(), NOW());
INSERT INTO video_agent_runs (id, media_id, goal, context_version, status) VALUES
 ('run-linked', 11, 'P0_PRIVATE_SENTINEL', 'v1', 'FAILED'),
 ('run-unlinked', 11, 'P0_PRIVATE_SENTINEL', 'v1', 'WAITING_FOR_QUOTA'),
 ('run-cross-media', 11, 'P0_PRIVATE_SENTINEL', 'v1', 'FAILED'),
 ('run-orphan', 999, 'P0_PRIVATE_SENTINEL', 'v1', 'FAILED');
INSERT INTO video_messages (id, conversation_id, message_type, content, agent_run_id, created_at) VALUES
 (31, 21, 'AGENT_RUN', 'P0_PRIVATE_SENTINEL', 'run-linked', NOW()),
 (32, 22, 'AGENT_RUN', 'P0_PRIVATE_SENTINEL', 'run-cross-media', NOW()),
 (33, 999, 'USER', 'P0_PRIVATE_SENTINEL', NULL, NOW()),
 (34, 21, 'AGENT_RUN', 'P0_PRIVATE_SENTINEL', 'run-missing', NOW()),
 (35, 21, 'USER', 'P0_PRIVATE_SENTINEL', NULL, NOW());
INSERT INTO video_agent_steps (run_id, stage, round_no, status)
 VALUES ('run-missing', 'NOTES', 0, 'SUCCEEDED');
SET FOREIGN_KEY_CHECKS = 1;
""", DATABASE)
        result = subprocess.run(
            ["bash", str(ROOT / "scripts/study-agent-p0-audit.sh")],
            env={**os.environ, "P0_MYSQL_CONTAINER": CONTAINER, "P0_MYSQL_DATABASE": DATABASE},
            text=True, capture_output=True, check=True,
        )
        records = [json.loads(line) for line in result.stdout.splitlines()]
        sections = {r["section"]: r["data"] for r in records}
        assert sections["schema"]["read_only"] == 1
        assert sections["schema"]["latest_flyway_version"] == "18"
        assert sections["integrity"] == {
            "media_without_owner": 1, "media_with_missing_user": 1,
            "conversations_without_media": 1, "runs_without_media": 1,
            "messages_without_conversation": 1, "messages_with_missing_run": 1,
            "steps_without_run": 1, "cross_media_run_links": 1,
            "runs_without_timeline_link": 2, "runs_with_duplicate_timeline_links": 0,
        }
        assert sections["mapping_totals"]["conversations_with_candidate_owner"] == 2
        assert sections["mapping_totals"]["runs_with_candidate_owner"] == 3
        assert sections["active_summary"]["active_runs"] == 1
        assert MARKER not in result.stdout
        for section in ("conversation_mapping_preview", "run_mapping_preview", "active_run_preview"):
            assert sum(r["section"] == section for r in records) <= 20
        # Exercise the limit with more than 20 rows rather than only checking a small fixture.
        conversations = ",".join(
            f"({1000+i},11,'{MARKER}',NOW(),NOW())" for i in range(25)
        )
        runs = ",".join(
            f"('extra-{i}',11,'{MARKER}','v1','WAITING_FOR_QUOTA')" for i in range(25)
        )
        mysql("INSERT INTO video_conversations (id,media_id,title,created_at,updated_at) VALUES "
              + conversations + "; INSERT INTO video_agent_runs (id,media_id,goal,context_version,status) VALUES "
              + runs + ";", DATABASE)
        bounded = mysql((ROOT / "scripts/study-agent-p0-audit.sql").read_text(), DATABASE)
        bounded_records = [json.loads(line) for line in bounded.stdout.splitlines()]
        for section in ("conversation_mapping_preview", "run_mapping_preview", "active_run_preview"):
            assert sum(r["section"] == section for r in bounded_records) == 20
        assert MARKER not in bounded.stdout
        sql = (ROOT / "scripts/study-agent-p0-audit.sql").read_text()
        probe = sql.replace("ROLLBACK;", "INSERT INTO users (username,password) VALUES ('must-not-write','synthetic');\nROLLBACK;")
        rejected = mysql(probe, DATABASE, check=False)
        assert rejected.returncode != 0 and "1792" in rejected.stderr
        assert mysql("SELECT COUNT(*) FROM users;", DATABASE).stdout.strip() == "3"
        print("P0 audit checks passed: anomaly counts, candidate mappings, privacy, bounded previews, read-only enforcement.")
    finally:
        if created:
            assert DATABASE.startswith("vediomind_p0_test_")
            mysql("DROP DATABASE `" + DATABASE + "`;")


if __name__ == "__main__":
    main()
