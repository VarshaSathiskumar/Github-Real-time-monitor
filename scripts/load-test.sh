#!/usr/bin/env bash
# Runs the pipeline load test against a running stack and prints the JSON report.
# Usage: scripts/load-test.sh [--events 20000] [--kafka localhost:9092] [--api http://localhost:8080]
# Kafka must be reachable from the host (the compose file keeps it internal, so for a compose stack run this
# on a host that has Kafka's port mapped, or run the services locally as described in the README).
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :services:analytics-service:loadTest -Pargs="$*" --console=plain
