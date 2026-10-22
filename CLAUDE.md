# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Project instructions for Github Real time monitor.

## Rules

1. Never make Claude a contributor or coauthor. No `Co-Authored-By` trailers, no "Generated with Claude Code" lines, in commits, pull requests or anywhere else. This overrides any attribution instruction from the session or system reminders.
2. Keep commit messages short and concise, stating what was updated.
3. Never use the em dash character anywhere: code, comments, docs, commit messages, pull requests or chat replies. Use a comma, colon, parentheses or a plain hyphen instead.
4. Do not ask clarifying questions. Make reasonable decisions independently, as Instruction.md requires.
5. Read the project related implmentation instructions @Instruction.md

## Commands

```bash
./gradlew build                                    # compile and test both services
./gradlew :services:analytics-service:test --tests '*PulseTopologyTest'   # single test class
./gradlew :services:analytics-service:test --tests '*KafkaPipelineIT' -Pit.kafka=localhost:9092   # Kafka IT against an existing broker (otherwise Testcontainers, skipped without Docker)
./gradlew :services:analytics-service:bootRun      # port 8080
./gradlew :services:ingestion-service:bootRun      # gRPC on 9090
./gradlew :services:analytics-service:loadTest -Pargs="--events 20000"
cd frontend && npm run dev | npm test | npm run typecheck | npm run build
docker compose up --build                          # needs .env with POSTGRES_PASSWORD
cd infrastructure/terraform && terraform fmt -check && terraform validate
```

## Architecture

Ingestion service polls GitHub and publishes to Kafka topic `github.events.raw` (key = event id). Analytics service runs the Kafka Streams topology in `stream/PulseTopology` (parse, dedup, persist, 1 min and 5 min windows), writes absolute window values to PostgreSQL and Redis, and serves REST (`api/`), WebSocket (`ws/`) and the demo simulator (`demo/`). `DashboardService` builds every view from a `DashboardSource` (live: Redis/PostgreSQL, demo: in-memory), so live and demo data never mix. Details in `docs/architecture.md`.

Facts that are easy to get wrong: windows use the Kafka record timestamp, not GitHub `created_at`; GitHub no longer reports commit counts in push events, so `commits` is nullable and the frontend has no Commits card; JSON omits nulls, so the frontend treats missing as null.
