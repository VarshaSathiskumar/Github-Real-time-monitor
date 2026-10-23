# Architecture notes

## Services

### Ingestion service (`services/ingestion-service`)

- `PollingService` is a self-rescheduling loop. The delay after each cycle is `max(X-Poll-Interval, min-poll-interval)`; on errors it backs off exponentially (capped at 5 minutes, with jitter); on `403/429` it waits for `Retry-After` or `X-RateLimit-Reset`; when the remaining quota drops to the configured reserve it sleeps until reset.
- Each page keeps its own ETag. An unchanged first page (`304`) ends the cycle, because older pages cannot have changed either.
- New events are published oldest first. **ETags are committed only after Kafka acknowledged every record of the cycle**, so a failed publish is retried on the next cycle (at-least-once). A bounded LRU of published IDs removes the overlap between consecutive polls.
- Every event gets an `ingested_at` timestamp. Record key = GitHub event ID.
- `IngestionStatusService` holds status, last successful poll, event count, last error and failure streak; it is exposed over gRPC (`GetIngestionStatus`) and used for the dashboard's pipeline panel.
- Graceful shutdown stops the poller, drains the Kafka producer and shuts the gRPC server down.

### Analytics service (`services/analytics-service`)

Packages: `stream` (topology), `store` (Redis, PostgreSQL, sinks), `service` (view models), `api` (REST), `ws` (WebSocket), `demo` (simulator), `grpc` (client).

## Kafka Streams topology

```
raw ──parse──┬─ malformed ───────────────► github.events.dlq
             └─ dedup (state store, key = event id, TTL 2 h)
                   └─ persist (3 retries) ─┬─ failed ──────► github.events.dlq
                                           └─ NEW events only
                                                ├─ 1 min window, all events        -> minute aggregates
                                                ├─ 1 min window, per repository    -> repository aggregates
                                                └─ 5 min window, per repository    -> momentum detector
```

- Internal topics: `github.events.raw` (3 partitions) and `github.events.dlq` (1) are created by the services through `KafkaAdmin`; Kafka Streams adds its own repartition and changelog topics.
- Only events whose insert into PostgreSQL was *new* feed the aggregations, so duplicates cannot inflate analytics even after state loss.
- Uncaught stream thread exceptions replace the thread instead of stopping the service. Sink failures inside aggregations are logged and healed by the next window update (values are absolute).

### Time semantics and late events

Windows are driven by the Kafka record timestamp, i.e. the moment the ingestion service published the event. GitHub's `created_at` is stored and shown but is not used for windowing, because the public feed is delayed. Grace period is 30 s; Kafka Streams drops records later than that from a window and counts them in its `dropped-records` metric. Window stores keep 2 hours.

### Delivery guarantees

| Hop | Guarantee |
|---|---|
| GitHub to Kafka | at-least-once (ETag committed after acks; idempotent producer, `acks=all`) |
| Kafka to PostgreSQL | at-least-once, made idempotent by `ON CONFLICT (event_id) DO NOTHING` |
| Aggregates to PostgreSQL/Redis | absolute upserts; PostgreSQL keeps `GREATEST(old, new)` |
| End to end | **not** exactly-once |

### Momentum detection

For each repository and 5-minute window the processor stores the last few window counts. A repository is flagged when the current count is at least `min-events` (8) and at least `spike-ratio` (3x) the mean of up to `baseline-windows` (6) previous windows, and only once `min-history-windows` (3) windows have been observed. If the baseline is below `min-baseline` (1 event per window), no percentage is reported and the entry is labelled `BURST`. Levels: `WARMING` (< 200%), `RISING` (>= 200%), `SURGING` (>= 500%). Rules live in `MomentumRules`, shared by the stream processor and the demo.

## Storage

### PostgreSQL (Flyway `V1__init.sql`)

`github_events`, `repositories`, `minute_aggregates`, `repo_minute_aggregates`, `rising_repositories`, `processing_failures`. Only the fields the dashboard needs are stored. Indexes cover time, repository and kind lookups. `RetentionJob` runs hourly: events 48 h, repository minutes 72 h, minute aggregates 30 d, spikes 7 d, failures 14 d.

### Redis

| Key | Type | Content |
|---|---|---|
| `pulse:min:<epochMinute>` | string | compact counters for that minute |
| `pulse:repomin:<epochMinute>` | hash | repository to compact counters |
| `pulse:repos:<epochMinute>` | HyperLogLog | distinct repositories (active repositories KPI) |
| `pulse:recent` | list | latest events (JSON), capped |
| `pulse:rising` | hash | repository to spike JSON |

All keys expire (26 h; `rising` 2 h). Windows up to one hour are read from Redis, longer ranges from PostgreSQL.

## REST and WebSocket

REST serves the initial load and fallback polling. A change of range or mode re-fetches a snapshot.

### WebSocket protocol

Endpoint `/ws`. Client to server:

```json
{"action": "subscribe", "mode": "live", "range": "1h"}
```

Server to client envelope: `{"type", "mode", "range", "ts", "data"}`. Types: `hello`, `kpi`, `activity`, `trending`, `breakdown`, `rising`, `status` (one snapshot per tick for each distinct mode/range subscription, default every 3 s), `events` (batch of up to 20 new events, newest first) and `alert` (a spike seen for the first time).

On reconnect the frontend backs off exponentially, re-subscribes and fetches a fresh REST snapshot to recover anything missed. While the socket is down it polls REST every 10 s.

## Demo mode

`DemoSimulator` backfills six hours of minute buckets, then emits roughly four events per second plus a periodic 100 s spike on a quiet repository. State lives in `DemoDataStore`, an in-memory implementation of the same `ActivityStore`/`DashboardSource` interfaces the real data uses, so all view models are computed by identical code. It runs while a demo client is connected (or for 90 s after the last `POST /api/demo/start`) and then discards its data.

## Network graph

The landing page is frontend only; it needs no new backend API. `useDashboard` exposes a `subscribeEvents` tap on the existing `events` WebSocket messages and REST snapshots, and `useGraph` folds them into a `GraphStore` (`lib/graph.ts`):

- Nodes are repositories, developers and events; edges link developer to repository (weighted by event count) and each event to its repository and actor. Event ids are deduplicated, and live and demo data never share a store (it is cleared on mode change).
- Limits: 60 repositories, 120 developers, 200 events. Least recently active repositories and developers are evicted with their events, developers left without a repository are dropped, and the oldest events go first.
- Only the 20 freshest events are drawn by default. Selecting a repository expands all of its events; selecting a developer shows theirs.
- `GraphCanvas` (Cytoscape.js) diffs the visible elements against the canvas and adds or removes only what changed, while `ForceLayout` (d3-force) keeps running and is re-heated rather than rebuilt, so positions stay stable as events stream in.
- The store lives in `App`, so the graph keeps updating while the Analytics tab is open. The active tab is mirrored in `?view=analytics`.
