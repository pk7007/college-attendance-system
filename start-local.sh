#!/usr/bin/env bash
# Starts CampusAttend on macOS or Linux without Docker. Needs JDK 21+ and a running MySQL 8 server.
# Usage: ./start-local.sh [config-file]   (default: local-config.env, copied from local-config.example.env)
set -euo pipefail
cd "$(dirname "$0")"

config="${1:-local-config.env}"
if [ ! -f "$config" ]; then
  echo "Copy local-config.example.env to local-config.env and set CAM_DB_PASSWORD first." >&2
  exit 1
fi
set -a; . "./$config"; set +a
if [ -z "${CAM_DB_PASSWORD:-}" ] || [ "$CAM_DB_PASSWORD" = "REPLACE_WITH_YOUR_DATABASE_PASSWORD" ]; then
  echo "Set CAM_DB_PASSWORD in $config." >&2
  exit 1
fi

java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
version="$("$java_bin" -version 2>&1 | awk -F'"' '/version/ {print $2}' | cut -d. -f1 || true)"
if ! [ "${version:-0}" -ge 21 ] 2>/dev/null; then
  echo "Install JDK 21 (or newer) and set JAVA_HOME. Found: ${version:-no Java}." >&2
  exit 1
fi

host="${CAM_DB_HOST:-127.0.0.1}" port="${CAM_DB_PORT:-3306}"
if ! (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null; then
  echo "MySQL is not reachable at $host:$port. Start the MySQL service and check CAM_DB_HOST/CAM_DB_PORT." >&2
  exit 1
fi
export CAM_DB_URL="jdbc:mysql://$host:$port/${CAM_DB_NAME:-college_attendance}?serverTimezone=UTC"

cd backend
chmod +x mvnw
./mvnw --batch-mode verify
echo "Starting http://localhost:${PORT:-8080}. Wait for 'Started AttendanceApplication', then open it in a browser. Ctrl+C stops the server."
exec "$java_bin" -jar target/attendance-api-1.0.0.jar
