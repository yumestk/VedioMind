#!/usr/bin/env bash
# Offline read-only inventory. Does not start Spring, Flyway, MQ or AI calls.
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
audit_container="${P0_MYSQL_CONTAINER:-mysql-media}"
audit_database="${P0_MYSQL_DATABASE:-media_db}"
if [[ ! "$audit_database" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo 'P0_MYSQL_DATABASE must be a simple database name.' >&2
  exit 2
fi
exec docker exec -i "$audit_container" sh -c '
  MYSQL_PWD="${MYSQL_ROOT_PASSWORD:?Container database credential is required}"
  export MYSQL_PWD
  exec mysql --user=root --database="$1" --batch --raw --skip-column-names
' sh "$audit_database" < "$project_dir/scripts/study-agent-p0-audit.sql"
