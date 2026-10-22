#!/usr/bin/env bash
# Starts the whole stack with Docker Compose and waits until the dashboard answers.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env from .env.example (edit POSTGRES_PASSWORD and, optionally, GITHUB_TOKEN)."
fi

docker compose up --build -d
port=$(grep -E '^HTTP_PORT=' .env | cut -d= -f2 || true)
url="http://localhost:${port:-8088}"

echo "Waiting for ${url} ..."
for _ in $(seq 1 90); do
  if curl -fs "${url}/api/system/status" > /dev/null 2>&1; then
    echo "GitHubPulse is up: ${url}"
    exit 0
  fi
  sleep 3
done
echo "Timed out. Inspect logs with: docker compose logs -f" >&2
exit 1
