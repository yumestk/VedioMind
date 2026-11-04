#!/usr/bin/env bash
# Stop the application before running. Existing P0 audit and human approval are required.
set -euo pipefail
if [[ $# != 2 ]]; then
  echo 'Usage: study-agent-p1-upgrade.sh <confirmed-owner-id> <expected-unlinked-run-count>' >&2
  exit 2
fi
[[ "$1" =~ ^[0-9]+$ && "$2" =~ ^[0-9]+$ ]] || exit 2
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir/server"
./mvnw -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/auth-classpath.txt </dev/null
task_classpath="target/classes:$(cat target/auth-classpath.txt)"
task_database="${P1_MYSQL_DATABASE:-media_db}"
[[ "$task_database" =~ ^[A-Za-z0-9_]+$ ]] || exit 2
task_backup_dir="${P1_BACKUP_DIR:-$HOME/.vediomind/backups}"
mkdir -p "$task_backup_dir"
chmod 700 "$task_backup_dir"
task_backup="$task_backup_dir/study-p1-$(date +%Y%m%d-%H%M%S)-$RANDOM.enc"
docker exec "${P1_MYSQL_CONTAINER:-mysql-media}" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --no-tablespaces "$1"' sh "$task_database" \
  | java -cp "$task_classpath" com.example.server.security.AuthAdmin backup-db "$task_backup"
printf 'Encrypted backup: %s\n' "$task_backup"
exec java -cp "$task_classpath" com.example.server.security.StudyDataUpgrade "$1" "$2" "$task_database" "$task_backup"
