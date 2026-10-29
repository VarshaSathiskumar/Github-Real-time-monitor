# Serverless lite deployment (Vercel and Neon)

A public demo that runs on free tiers only: the React app and a few Vercel Functions on Vercel, data in Neon PostgreSQL. It is a **different, smaller architecture** than the one in [architecture.md](architecture.md). The Kafka, Kafka Streams, Redis, WebSocket and gRPC implementation is unchanged and is still what `docker compose up` runs.

## Full architecture versus serverless lite

| | Full (Docker Compose, Terraform) | Serverless lite (Vercel + Neon) |
|---|---|---|
| Ingestion | Always-on Spring Boot service polling GitHub | A Vercel Function polls GitHub **only while someone has the dashboard open** |
| Event transport | Kafka topic `github.events.raw`, DLQ topic | None: events are written straight to PostgreSQL |
| Aggregation | Kafka Streams windows (1 min, 5 min) | One SQL statement per poll adds the counts of the newly inserted events |
| Live window | Redis counters | PostgreSQL |
| Refresh | WebSocket push every 3 s | REST polling of one `/api/snapshot` endpoint, every 10 s by default |
| Ingestion health | gRPC `GetIngestionStatus` | `ingestion_state` row in PostgreSQL |
| Demo mode | In-memory simulator in the analytics service | Stateless generator inside the function (no database, no GitHub) |
| History | Continuous | Only the periods when somebody was watching |
| Benchmarks | `docs/load-testing.md` | **None. The load-test figures do not apply and must not be quoted for this deployment.** |

## How it works

```
Browser ── GET /api/snapshot?mode&range (every 10 s, paused when hidden or idle)
   │
   ▼  Vercel CDN: s-maxage=10
Vercel Function  (frontend/api/snapshot.ts, maxDuration 30 s)
   ├─ mode=demo: generated from the clock, no database access
   └─ mode=live: 1. read the views and the ingestion state from Neon, respond
                 2. after the response (waitUntil), if a poll is due:
                      claim → fetch GitHub → parse → one write statement → release
```

The code is in `frontend/api/_lib/`:

| File | Port of |
|---|---|
| `parser.ts` | `EventParser` |
| `momentum.ts` | `MomentumRules` |
| `views.ts` | `DashboardService`, `Buckets` |
| `postgres.ts` | `PostgresActivityStore`, the `LiveSource` queries |
| `memory.ts`, `demo.ts` | `DemoDataStore`, `DemoSimulator` |
| `ingest.ts` | `PollingService` plus the persist and aggregate stages of `PulseTopology` |

### Ingestion is fault tolerant

- **Atomic claim with expiry.** A poll starts with `UPDATE ingestion_state SET claim_token = ..., claim_expires_at = now() + 40 s WHERE next_poll_at <= now() AND (claim IS NULL OR claim expired)`. Exactly one request wins; the others carry on serving data. The 40 s expiry is longer than the function's 30 s `maxDuration`, so a live poll is never taken over.
- **Recovery.** If a function dies mid-poll, nothing releases the claim; it expires and the next visitor takes over. The release and failure updates are conditional on the claim token, so a slow holder whose claim expired cannot overwrite its successor's state.
- **Dashboard availability.** The response is built and sent before ingestion starts. `maybeIngest` never throws: any failure is stored in `ingestion_state` (`DEGRADED`, `FAILING` after 3, `RATE_LIMITED`), the claim is released and `next_poll_at` is pushed out with the same backoff as the Java service (60 s doubling to 5 min with jitter; `Retry-After` / `X-RateLimit-Reset` for rate limits). The dashboard keeps showing what is stored. A database outage returns a non-cached 503.
- **At-least-once, idempotent.** ETags move forward only after the events of a poll are stored, so a failed write is fetched again. `INSERT ... ON CONFLICT (event_id) DO NOTHING ... RETURNING` is the deduplication.
- **Aggregates only for new events.** The same statement feeds the `RETURNING` rows into `minute_aggregates` and `repo_minute_aggregates` (`total = total + new`). A replayed or duplicated event is not returned, so it is never counted, and events and aggregates commit or fail together.
- **Poison events.** Malformed events (and values wider than their columns) go to `processing_failures` with stage `parse` or `persist`, like the DLQ in the full architecture. If the batch statement fails for a reason tied to specific events (for example a NUL character), the batch is split until the offending events are alone. If the database itself is down, nothing is split and nothing is recorded as poison.

### Semantics kept from the Java services

- Windows use **observation time**, the moment the poll stored the event, like the Kafka record timestamp. GitHub's `created_at` is display only.
- The momentum rule is the same code path as `MomentumRules` (5 minute windows, six baseline windows, minimum events, minimum baseline, BURST without a percentage). It runs for the repositories that received events in each poll.
- Deduplication, the activity score and its weights (`SCORE_WEIGHT_*`), `MOMENTUM_MIN_EVENTS`, `GITHUB_PAGES` and `GITHUB_MIN_POLL_INTERVAL` use the same names and defaults.

Known differences: spike detection runs once per poll instead of on every Kafka Streams commit; there is no per-repository `repositories` table write (no view reads it); retention is shorter (events 3 h, repository minutes 25 h, minute totals 30 d).

## Free tier limits that shaped the design

Checked against the vendors' documentation on 2026-10-28. Limits change, re-check before relying on them.

| Service | Limit | Consequence |
|---|---|---|
| Vercel Hobby cron | once per day | Cannot drive ingestion, hence visitor-triggered polling |
| Vercel Hobby functions | 1,000,000 invocations/month, 4 h active CPU, 300 s max duration | One combined endpoint, CDN caching, polling limits |
| Vercel Hobby | non-commercial use only | Fine for a portfolio demo |
| Neon Free | 1 GB storage, 100 CU-hours/month per project, scale to zero after 5 min idle, 5 GB transfer | The database cannot be awake all month, so it is only woken by visitors |
| GitHub events API | 300 events max, latency 30 s to 6 h, follow `X-Poll-Interval` | Unchanged: events are a delayed sample |

Cost controls built in:

- `/api/snapshot` returns every view in one response: one invocation per refresh instead of seven.
- `Cache-Control: public, max-age=0, s-maxage=10` lets Vercel's CDN absorb repeated requests; each function instance also reuses a built snapshot for 5 s per (mode, range), so cache-busting query strings cannot wake the database more than that.
- The browser refreshes every 10 s (`VITE_POLL_INTERVAL_MS`, never below 5 s), not at all while the tab is hidden, and stops after 10 minutes without interaction with a visible notice.
- GitHub is polled at most once per `X-Poll-Interval` (minimum 60 s), and ingestion piggybacks on snapshot requests, so it adds no invocations of its own.
- Demo mode never touches Neon.

Arithmetic, not measurements: an actively used tab makes 6 requests/minute, about 260,000 per month if it were active 24/7 (one tab only; the idle pause bounds this in practice). Neon's plan page quotes 100 CU-hours as 400 hours at 0.25 CU, so a database kept awake 24/7 (about 730 hours) would not fit; I have not verified Neon's minimum compute size or whether Vercel counts CDN cache hits as invocations, nor measured the active CPU per request on Vercel. Watch the usage pages of both dashboards after deploying.

Storage, measured with the real write path on PostgreSQL (all repositories distinct, 300 events/minute, the 3 page rate with a token): `github_events` 458 bytes/event, `repo_minute_aggregates` 345 bytes/row. At the configured retention that is roughly 25 MB plus 155 MB, well under 1 GB, and less in practice because ingestion only runs while the dashboard is open.

## Set up

1. **Neon.** Create a project (choose the AWS US East 1 region, next to Vercel's default function region `iad1`). Copy the connection string.
2. **Schema.** From `frontend/`: `DATABASE_URL='postgres://...' npm run db:migrate`. It applies `V1__init.sql` (the same file Flyway uses in Docker Compose) and `api/_lib/lite-schema.sql`. Re-running is safe.
3. **GitHub token (recommended).** A token without scopes raises the limit from 60 to 5,000 requests/hour and enables three pages per poll. Unauthenticated requests from a shared hosting provider's addresses may share one small allowance.
4. **Vercel project.** Import the repository, Root Directory `frontend` (already described in [vercel.md](vercel.md)). Environment variables:

| Variable | Where | Value |
|---|---|---|
| `DATABASE_URL` | server only | Neon connection string |
| `GITHUB_TOKEN` | server only | optional, see above |
| `VITE_REFRESH_MODE` | build time | `snapshot` |
| `VITE_POLL_INTERVAL_MS` | build time, optional | default `10000`, minimum `5000` |

Leave `VITE_API_BASE_URL` empty: the functions are served from the same origin. Never put the database URL or the token in a `VITE_*` variable, those end up in public JavaScript.

5. **Verify** (do not call the deployment working before this):
   - `curl 'https://<app>/api/snapshot?mode=demo'` returns JSON with `"mode":"demo"`.
   - `curl 'https://<app>/api/snapshot?mode=live'` returns 200; after a minute `status.ingestion.status` is `HEALTHY` and `eventsIngested` grows (if it shows `RATE_LIMITED` or `DEGRADED`, `lastError` says why).
   - Open the site, check the "Serverless lite deployment" notice, the live feed, **Launch Demo Activity** and **Return to Live Data**.

## Tests

```bash
cd frontend && npm test          # includes the api/ tests, run on an in-process PostgreSQL (PGlite), no Docker
./gradlew :services:analytics-service:test --tests '*ServerlessParityTest'
```

- `parity/` holds shared fixtures: raw GitHub events (valid and malformed), momentum rule cases and dashboard scenarios (inputs), and the expected outputs **generated by the Java code**. `ServerlessParityTest` asserts them in Java, `api/_lib/parity.test.ts` asserts them in TypeScript, including the SQL store fed through the real write path.
- After an intentional behaviour change in Java run `PARITY_UPDATE=1 ./gradlew :services:analytics-service:cleanTest :services:analytics-service:test --tests '*ServerlessParityTest'`, review the diff of `parity/*.expected.json` and make the TypeScript pass again.
- `ingest.test.ts` covers claims (single winner, expiry recovery, stale holder), failure backoff, rate limits, ETags, deduplication, poison events, momentum and retention. `snapshot.test.ts` covers demo independence, availability while ingestion fails, caching and validation.

## Limitations

- Data is only collected while someone watches; an idle site shows an empty 1 h window until the first visitor has been there a while. Use **Launch Demo Activity** for an immediately full dashboard.
- Refresh is polling every 10 s, not push. The latency and throughput of the Kafka pipeline were measured only for that pipeline.
- One shared poller per deployment: the visitors all see the same sample.
- The activity feed order of events inside one poll is by event id, since they share the same observation time.
- Not exercised against a real Neon database or a real Vercel deployment by the author: see the checklist above.
