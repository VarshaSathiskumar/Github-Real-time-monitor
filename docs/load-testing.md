# Load testing

The load test is a Java program in the analytics service's test sources, run with Gradle:

```bash
# stop the ingestion service first so real GitHub traffic does not pollute the counts
./gradlew :services:analytics-service:loadTest -Pargs="--events 20000"
# options: --events N  --latency-events N  --kafka host:port  --api http://host:port  --out file.json
```

It needs a running stack (Kafka, Redis, PostgreSQL, analytics service) and Kafka reachable from the machine running the test.

## What is measured

| Metric | How |
|---|---|
| Events processed per second | A burst of N unique events is produced; the clock stops when `GET /api/metrics` counts all N. Includes dedup, one PostgreSQL insert per event, windowed aggregation and the Redis write. |
| Event processing latency | 300 marker events at 20/s; time from `producer.send` until `GET /api/events/recent` (polled every 25 ms) shows the event. |
| API response latency | 100 sequential requests per endpoint after a warm-up; p50/p95/p99. |
| WebSocket update delay | Same markers, time until a pushed `events` message contains the marker; plus the interval between consecutive `kpi` messages. |
| Consumer lag | `/api/system/status` sampled every second during the burst (log end offset minus committed offset). |

## Results (one run, development machine)

Environment: Apple M3, 8 cores, 16 GB RAM; single local Kafka broker (KRaft), Redis and PostgreSQL 16 installed with Homebrew; both services and the load test on the same machine; JDK 24 HotSpot. These numbers are what that run printed, not a benchmark of production hardware: treat them as a baseline for comparing changes.

| Metric | Result |
|---|---|
| End-to-end throughput (20,000 events) | **768 events/s** (all 20,000 reflected in 26.0 s) |
| Producer rate (for reference) | about 166,000 events/s, so Kafka is not the bottleneck |
| Peak consumer lag during the burst | 17,850 records, drained as processing caught up |
| Send to visible via REST (latency) | p50 **26 ms**, p95 52 ms, p99 70 ms, max 278 ms |
| Send to pushed over WebSocket | p50 661 ms, p95 2.5 s, p99 2.9 s (dominated by the 3 s broadcast interval) |
| WebSocket `kpi` message interval | about 3.04 s |

API latency (ms, 100 sequential requests each, small data set, so mostly cache and Redis reads):

| Endpoint | p50 | p95 | p99 |
|---|---|---|---|
| `/api/metrics?range=1h` | 1 | 6 | 17 |
| `/api/activity?range=1h` | 0 | 0 | 1 |
| `/api/activity?range=24h` (PostgreSQL, memoised 10 s) | 0 | 0 | 0 |
| `/api/repositories/trending?range=1h` | 3 | 6 | 10 |
| `/api/events/recent` | 0 | 1 | 2 |
| `/api/activity/breakdown?range=1h` | 0 | 0 | 1 |
| `/api/repositories/rising` | 0 | 2 | 53 |
| `/api/system/status` | 0 | 0 | 1 |

Notes and caveats:

- The throughput ceiling is the one-insert-per-event PostgreSQL write on a single Kafka Streams thread. The real GitHub feed delivers only about 100 to 300 events per minute, far below this, so the headroom is large for the intended use.
- Only 134 of the 300 WebSocket markers arrived: each broadcast carries the 20 newest events, and the test sends about 60 events per 3 s tick. That is by design (the feed shows 20 rows).
- The 24 h API timings are taken on a database holding minutes of data, not a day, so they understate the cost of a full-sized window.
- Raw JSON of the last run is written to `scripts/results/loadtest.json` (git-ignored).
