#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir/server"
./mvnw -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/auth-classpath.txt </dev/null
exec java -cp "target/classes:$(cat target/auth-classpath.txt)" com.example.server.security.AuthAdmin "$@"
