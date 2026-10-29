import { randomUUID } from 'node:crypto'
import { type MomentumConfig, MINUTE } from './model.js'
import { evaluate } from './momentum.js'
import { type ParsedEvent, MalformedEventError, parseEvent } from './parser.js'
import { type Db, ms } from './postgres.js'

// Visitor-triggered ingestion: the serverless counterpart of PollingService + the Kafka Streams persist/aggregate
// stages. One request at a time holds an expiring claim, polls GitHub, and writes the new events and their
// aggregates in a single SQL statement.

export interface IngestConfig {
  apiUrl: string
  token: string
  pages: number
  perPage: number
  minPollSeconds: number
  maxBackoffSeconds: number
  requestTimeoutMs: number
  /** Must exceed the function's maxDuration so a live job is never reclaimed under its feet. */
  claimTtlSeconds: number
  rateLimitReserve: number
  dlqTopic: string
}

export function ingestConfigFromEnv(env: Record<string, string | undefined> = process.env): IngestConfig {
  const seconds = (v: string | undefined, d: number): number => {
    const n = v === undefined ? NaN : parseInt(v, 10)
    return Number.isFinite(n) && n > 0 ? n : d
  }
  const token = (env.GITHUB_TOKEN ?? '').trim()
  return {
    apiUrl: env.GITHUB_API_URL || 'https://api.github.com/events',
    token,
    // Without a token GitHub allows 60 requests per hour, so only the first page is polled.
    pages: token ? seconds(env.GITHUB_PAGES, 3) : 1,
    perPage: 100,
    minPollSeconds: seconds(env.GITHUB_MIN_POLL_INTERVAL, 60),
    maxBackoffSeconds: 300,
    requestTimeoutMs: 8_000,
    claimTtlSeconds: 40,
    rateLimitReserve: 3,
    dlqTopic: 'github.events.dlq',
  }
}

export interface Deps {
  db: Db
  momentum: MomentumConfig
  config?: IngestConfig
  fetch?: typeof fetch
  /** Wall clock used for event time (observed_at); the database clock is used for claims and scheduling. */
  now?: () => number
  log?: (message: string) => void
}

export type Outcome =
  | { result: 'skipped' }
  | { result: 'ok'; inserted: number; fetched: number; failed: number }
  | { result: 'failed'; error: string }

interface Claim {
  token: string
  etags: Record<string, string>
  failures: number
  firstWindowStart: number | null
}

class RateLimited extends Error {
  constructor(message: string, readonly waitSeconds: number, readonly remaining: number | null) {
    super(message)
  }
}

/** Takes the single ingestion claim, or returns null when it is not due yet or somebody else holds it. */
export async function claim(db: Db, ttlSeconds: number): Promise<Claim | null> {
  const token = randomUUID()
  const rows = await db(
    `UPDATE ingestion_state
        SET claim_token = $1, claim_expires_at = now() + make_interval(secs => $2::int), last_poll_attempt = now()
      WHERE id = 1 AND next_poll_at <= now() AND (claim_expires_at IS NULL OR claim_expires_at <= now())
      RETURNING etags, consecutive_failures, first_window_start`,
    [token, ttlSeconds],
  )
  const r = rows[0]
  if (!r) return null
  return {
    token,
    etags: (typeof r.etags === 'string' ? JSON.parse(r.etags) : r.etags) as Record<string, string>,
    failures: Number(r.consecutive_failures),
    firstWindowStart: r.first_window_start === null ? null : ms(r.first_window_start),
  }
}

interface Page {
  status: number
  events: unknown[]
  etag: string | null
  pollInterval: number | null
  remaining: number | null
  reset: number | null
  retryAfter: number | null
  body: string
}

async function fetchPage(cfg: IngestConfig, doFetch: typeof fetch, page: number, etag: string | undefined): Promise<Page> {
  const headers: Record<string, string> = {
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'GitHubPulse-Ingestion',
  }
  if (etag) headers['If-None-Match'] = etag
  if (cfg.token) headers.Authorization = `Bearer ${cfg.token}`
  const res = await doFetch(`${cfg.apiUrl}?per_page=${cfg.perPage}&page=${page}`, {
    headers,
    signal: AbortSignal.timeout(cfg.requestTimeoutMs),
  })
  const int = (h: string): number | null => {
    const v = parseInt(res.headers.get(h) ?? '', 10)
    return Number.isFinite(v) ? v : null
  }
  const body = res.status === 304 ? '' : await res.text()
  let events: unknown[] = []
  if (res.status === 200) {
    const json: unknown = JSON.parse(body)
    events = Array.isArray(json) ? json : []
  }
  return {
    status: res.status,
    events,
    etag: res.headers.get('etag'),
    pollInterval: int('x-poll-interval'),
    remaining: int('x-ratelimit-remaining'),
    reset: int('x-ratelimit-reset'),
    retryAfter: int('retry-after'),
    body: body.slice(0, 300),
  }
}

/** Same rule as FetchResult.rateLimited. */
const isRateLimited = (p: Page): boolean =>
  p.status === 429 || (p.status === 403 && (p.retryAfter !== null || p.remaining === 0))

function rateLimitWait(p: Page, cfg: IngestConfig, nowMs: number): number {
  if (p.retryAfter !== null) return p.retryAfter + 1
  if (p.reset !== null) {
    const wait = p.reset - Math.floor(nowMs / 1000)
    return wait < 0 ? cfg.minPollSeconds : wait + 1
  }
  return 60
}

// One statement inserts the batch and adds the counts of the events that were actually new to the aggregates.
// ON CONFLICT DO NOTHING is the dedup: a replayed event is not returned by RETURNING and so is never counted.
// Data-modifying CTEs always run to completion, so the aggregates and the events commit or fail together.
const WRITE_BATCH = `
WITH incoming AS (
  SELECT * FROM jsonb_to_recordset($1::jsonb) AS e(
    event_id bigint, event_type text, kind text, actor_login text, repo_id bigint, repo_name text, commits int,
    action text, description text, url text, created_at timestamptz, ingested_at timestamptz, observed_at timestamptz)
), ins AS (
  INSERT INTO github_events (event_id, event_type, kind, actor_login, repo_id, repo_name, commits, action,
                             description, url, created_at, ingested_at, observed_at)
  SELECT event_id, event_type, kind, actor_login, repo_id, repo_name, commits, action, description, url,
         created_at, ingested_at, observed_at FROM incoming
  ON CONFLICT (event_id) DO NOTHING
  RETURNING kind, repo_name, commits, observed_at
), per_minute AS (
  INSERT INTO minute_aggregates (minute, total, push, pull_request, fork, watch, issues, issue_comments, other,
                                 commits, commit_reports)
  SELECT date_trunc('minute', observed_at), count(*),
         count(*) FILTER (WHERE kind = 'PUSH'), count(*) FILTER (WHERE kind = 'PULL_REQUEST'),
         count(*) FILTER (WHERE kind = 'FORK'), count(*) FILTER (WHERE kind = 'WATCH'),
         count(*) FILTER (WHERE kind = 'ISSUES'), count(*) FILTER (WHERE kind = 'ISSUE_COMMENT'),
         count(*) FILTER (WHERE kind = 'OTHER'),
         coalesce(sum(commits) FILTER (WHERE kind = 'PUSH'), 0), count(commits) FILTER (WHERE kind = 'PUSH')
  FROM ins GROUP BY 1
  ON CONFLICT (minute) DO UPDATE SET
    total = minute_aggregates.total + EXCLUDED.total, push = minute_aggregates.push + EXCLUDED.push,
    pull_request = minute_aggregates.pull_request + EXCLUDED.pull_request, fork = minute_aggregates.fork + EXCLUDED.fork,
    watch = minute_aggregates.watch + EXCLUDED.watch, issues = minute_aggregates.issues + EXCLUDED.issues,
    issue_comments = minute_aggregates.issue_comments + EXCLUDED.issue_comments,
    other = minute_aggregates.other + EXCLUDED.other, commits = minute_aggregates.commits + EXCLUDED.commits,
    commit_reports = minute_aggregates.commit_reports + EXCLUDED.commit_reports, updated_at = now()
  RETURNING 1
), per_repo AS (
  INSERT INTO repo_minute_aggregates (minute, repo_name, total, push, pull_request, fork, watch, issues,
                                      issue_comments, other, commits, commit_reports)
  SELECT date_trunc('minute', observed_at), repo_name, count(*),
         count(*) FILTER (WHERE kind = 'PUSH'), count(*) FILTER (WHERE kind = 'PULL_REQUEST'),
         count(*) FILTER (WHERE kind = 'FORK'), count(*) FILTER (WHERE kind = 'WATCH'),
         count(*) FILTER (WHERE kind = 'ISSUES'), count(*) FILTER (WHERE kind = 'ISSUE_COMMENT'),
         count(*) FILTER (WHERE kind = 'OTHER'),
         coalesce(sum(commits) FILTER (WHERE kind = 'PUSH'), 0), count(commits) FILTER (WHERE kind = 'PUSH')
  FROM ins GROUP BY 1, 2
  ON CONFLICT (minute, repo_name) DO UPDATE SET
    total = repo_minute_aggregates.total + EXCLUDED.total, push = repo_minute_aggregates.push + EXCLUDED.push,
    pull_request = repo_minute_aggregates.pull_request + EXCLUDED.pull_request,
    fork = repo_minute_aggregates.fork + EXCLUDED.fork, watch = repo_minute_aggregates.watch + EXCLUDED.watch,
    issues = repo_minute_aggregates.issues + EXCLUDED.issues,
    issue_comments = repo_minute_aggregates.issue_comments + EXCLUDED.issue_comments,
    other = repo_minute_aggregates.other + EXCLUDED.other, commits = repo_minute_aggregates.commits + EXCLUDED.commits,
    commit_reports = repo_minute_aggregates.commit_reports + EXCLUDED.commit_reports, updated_at = now()
  RETURNING 1
)
SELECT repo_name, count(*)::int AS n FROM ins GROUP BY repo_name`

export interface Row {
  event_id: number
  event_type: string
  kind: string
  actor_login: string
  repo_id: number
  repo_name: string
  commits: number | null
  action: string | null
  description: string
  url: string
  created_at: string
  ingested_at: string
  observed_at: string
}

const toRow = (e: ParsedEvent, observedAt: number): Row => ({
  event_id: e.id,
  event_type: e.type,
  kind: e.kind,
  actor_login: e.actor,
  repo_id: e.repoId,
  repo_name: e.repoName,
  commits: e.commits,
  action: e.action,
  description: e.description.slice(0, 400),
  url: e.url.slice(0, 500),
  created_at: new Date(e.createdAt).toISOString(),
  ingested_at: new Date(e.ingestedAt).toISOString(),
  observed_at: new Date(observedAt).toISOString(),
})

/** Column widths of V1__init.sql; the Java persist stage would fail on these and route the event to the DLQ. */
const fits = (e: ParsedEvent): boolean =>
  e.type.length <= 40 && e.actor.length <= 100 && e.repoName.length <= 200 && (e.action ?? '').length <= 40

interface Failure {
  key: string
  stage: 'parse' | 'persist'
  reason: string
  payload: string
}

/**
 * Writes the batch, isolating poison events: if the statement fails the batch is split until the offending
 * events are alone, which are then recorded as failures instead of blocking everything behind them.
 */
export async function writeResilient(
  db: Db,
  rows: Row[],
  failures: Failure[],
  newRepos: Map<string, number>,
): Promise<number> {
  if (rows.length === 0) return 0
  try {
    const out = await db(WRITE_BATCH, [JSON.stringify(rows)])
    let inserted = 0
    for (const r of out) {
      const n = Number(r.n)
      inserted += n
      newRepos.set(String(r.repo_name), (newRepos.get(String(r.repo_name)) ?? 0) + n)
    }
    return inserted
  } catch (e) {
    // If the database itself is unavailable this throws, which is not the events' fault and must not be
    // mistaken for poison events.
    await db('SELECT 1')
    if (rows.length === 1) {
      failures.push({
        key: String(rows[0].event_id),
        stage: 'persist',
        reason: String((e as Error).message ?? e),
        payload: JSON.stringify(rows[0]),
      })
      return 0
    }
    const mid = Math.ceil(rows.length / 2)
    return (await writeResilient(db, rows.slice(0, mid), failures, newRepos)) +
      (await writeResilient(db, rows.slice(mid), failures, newRepos))
  }
}

/** Evaluates the momentum rule for the repositories that just received events, like the 5 minute window processor. */
async function detectSpikes(
  db: Db,
  momentum: MomentumConfig,
  repos: string[],
  observedAt: number,
  firstWindowStart: number | null,
): Promise<number> {
  if (repos.length === 0) return 0
  const sizeMs = momentum.windowMinutes * MINUTE
  const windowStart = Math.floor(observedAt / sizeMs) * sizeMs
  const rows = await db(
    `SELECT repo_name, (floor(extract(epoch from minute) / ($4::int * 60)) * $4::int * 60 * 1000)::bigint AS ws,
            sum(total)::bigint AS n
       FROM repo_minute_aggregates
      WHERE repo_name IN (SELECT jsonb_array_elements_text($1::jsonb))
        AND minute >= to_timestamp($2::double precision / 1000) AND minute < to_timestamp($3::double precision / 1000)
      GROUP BY 1, 2`,
    [JSON.stringify(repos), windowStart - momentum.baselineWindows * sizeMs, windowStart + sizeMs, momentum.windowMinutes],
  )
  const history = new Map<string, Map<number, number>>()
  for (const r of rows) {
    const h = history.get(String(r.repo_name)) ?? new Map<number, number>()
    h.set(Number(r.ws), Number(r.n))
    history.set(String(r.repo_name), h)
  }
  const first = firstWindowStart ?? windowStart
  const spikes = []
  for (const [repo, h] of history) {
    const s = evaluate(momentum, repo, windowStart, h.get(windowStart) ?? 0, h, first, observedAt)
    if (s) {
      spikes.push({
        repo_name: s.repo, window_start: new Date(s.windowStart).toISOString(), window_minutes: s.windowMinutes,
        current_count: s.currentCount, baseline: s.baseline, pct_increase: s.pctIncrease, level: s.level,
        detected_at: new Date(s.detectedAt).toISOString(),
      })
    }
  }
  if (spikes.length === 0) return 0
  await db(
    `INSERT INTO rising_repositories (repo_name, window_start, window_minutes, current_count, baseline, pct_increase,
                                      level, detected_at)
     SELECT * FROM jsonb_to_recordset($1::jsonb) AS s(repo_name text, window_start timestamptz, window_minutes int,
       current_count bigint, baseline double precision, pct_increase double precision, level text,
       detected_at timestamptz)
     ON CONFLICT (repo_name, window_start) DO UPDATE SET
       current_count = GREATEST(rising_repositories.current_count, EXCLUDED.current_count),
       baseline = EXCLUDED.baseline, pct_increase = EXCLUDED.pct_increase, level = EXCLUDED.level,
       detected_at = EXCLUDED.detected_at`,
    [JSON.stringify(spikes)],
  )
  return spikes.length
}

async function recordFailures(db: Db, topic: string, failures: Failure[]): Promise<void> {
  if (failures.length === 0) return
  const rows = failures.slice(0, 50).map((f) => ({
    topic,
    record_key: f.key,
    stage: f.stage,
    payload: JSON.stringify({ stage: f.stage, key: f.key, reason: f.reason, payload: f.payload, failedAt: new Date().toISOString() }).slice(0, 8000),
  }))
  await db(
    `INSERT INTO processing_failures (topic, record_key, stage, payload)
     SELECT * FROM jsonb_to_recordset($1::jsonb) AS f(topic text, record_key text, stage text, payload text)`,
    [JSON.stringify(rows)],
  )
}

/** Retention of the Docker setup, shortened to fit the 1 GB free database. Runs at most once an hour. */
async function cleanup(db: Db): Promise<void> {
  const due = await db(
    `UPDATE ingestion_state SET last_cleanup_at = now()
      WHERE id = 1 AND last_cleanup_at < now() - interval '1 hour' RETURNING 1`,
  )
  if (due.length === 0) return
  await db(`DELETE FROM github_events WHERE observed_at < now() - interval '3 hours'`)
  await db(`DELETE FROM repo_minute_aggregates WHERE minute < now() - interval '25 hours'`)
  await db(`DELETE FROM minute_aggregates WHERE minute < now() - interval '30 days'`)
  await db(`DELETE FROM rising_repositories WHERE window_start < now() - interval '7 days'`)
  await db(`DELETE FROM processing_failures WHERE failed_at < now() - interval '14 days'`)
}

const backoffSeconds = (cfg: IngestConfig, streak: number): number => {
  const capped = Math.min(cfg.minPollSeconds * 2 ** Math.min(streak, 6), cfg.maxBackoffSeconds)
  return Math.round(capped + Math.random() * (capped / 5))
}

/**
 * Polls GitHub once if a poll is due. Never throws: any failure is recorded in ingestion_state, the claim is
 * released with a backoff, and the dashboard keeps serving what is already stored.
 */
export async function maybeIngest(deps: Deps): Promise<Outcome> {
  const { db, momentum } = deps
  const cfg = deps.config ?? ingestConfigFromEnv()
  const doFetch = deps.fetch ?? fetch
  const clock = deps.now ?? Date.now
  const log = deps.log ?? ((m: string) => console.log(m))

  let held: Claim | null = null
  try {
    held = await claim(db, cfg.claimTtlSeconds)
    if (!held) return { result: 'skipped' }
    const { token } = held
    const failures: Failure[] = []

    try {
      const pending: Record<string, string> = {}
      const fresh: unknown[] = []
      let pollInterval = cfg.minPollSeconds
      let remaining: number | null = null
      let resetAt: number | null = null

      for (let page = 1; page <= cfg.pages; page++) {
        const res = await fetchPage(cfg, doFetch, page, held.etags[String(page)])
        if (res.pollInterval !== null) pollInterval = Math.max(pollInterval, res.pollInterval)
        if (res.remaining !== null) {
          remaining = res.remaining
          resetAt = res.reset
        }
        if (isRateLimited(res)) {
          throw new RateLimited(`GitHub rate limit hit (HTTP ${res.status})`, rateLimitWait(res, cfg, clock()), remaining)
        }
        if (res.status === 304) {
          if (page === 1) break // nothing new on the newest page means nothing new on older pages
          continue
        }
        if (res.status !== 200) throw new Error(`GitHub returned HTTP ${res.status}: ${res.body}`)
        if (res.etag) pending[String(page)] = res.etag
        fresh.push(...res.events)
        if (res.events.length < cfg.perPage) break
      }

      // Event time is the moment of observation (the Kafka record timestamp in the full architecture).
      const observedAt = clock()
      const rows: Row[] = []
      const seen = new Set<number>()
      for (const raw of fresh.reverse()) {
        try {
          const withIngest = typeof raw === 'object' && raw !== null ? { ...raw, ingested_at: new Date(observedAt).toISOString() } : raw
          const e = parseEvent(withIngest)
          if (!fits(e)) throw new MalformedEventError('Field longer than its column')
          if (seen.has(e.id)) continue
          seen.add(e.id)
          rows.push(toRow(e, observedAt))
        } catch (err) {
          if (!(err instanceof MalformedEventError)) throw err
          const id = typeof raw === 'object' && raw !== null ? String((raw as { id?: unknown }).id ?? 'no-id') : 'no-id'
          failures.push({ key: id, stage: 'parse', reason: err.message, payload: JSON.stringify(raw) ?? String(raw) })
        }
      }

      const newRepos = new Map<string, number>()
      const inserted = await writeResilient(db, rows, failures, newRepos)
      let spikes = 0
      try {
        spikes = await detectSpikes(db, momentum, [...newRepos.keys()], observedAt, held.firstWindowStart)
      } catch (err) {
        // Aggregates are already stored; a missed spike check only delays the momentum card.
        log(`Spike detection skipped: ${String(err)}`)
      }
      try {
        await recordFailures(db, cfg.dlqTopic, failures)
      } catch (err) {
        log(`Could not record ${failures.length} failed events: ${String(err)}`)
      }

      let delay = pollInterval
      if (remaining !== null && remaining <= cfg.rateLimitReserve && resetAt !== null) {
        delay = Math.max(delay, resetAt - Math.floor(clock() / 1000) + 1)
      }
      const sizeMs = momentum.windowMinutes * MINUTE
      // ETags move only now that every new event is stored, so a failed write is fetched again (at-least-once).
      await db(
        `UPDATE ingestion_state SET
            etags = etags || $2::jsonb, next_poll_at = now() + make_interval(secs => $3::int),
            claim_token = NULL, claim_expires_at = NULL, status = 'HEALTHY', last_error = '',
            consecutive_failures = 0, last_successful_poll = now(), poll_interval_seconds = $4::int,
            rate_limit_remaining = coalesce($5::int, rate_limit_remaining), events_ingested = events_ingested + $6::int,
            first_window_start = CASE WHEN $6::int > 0 THEN coalesce(first_window_start, to_timestamp($7::double precision / 1000))
                                      ELSE first_window_start END
          WHERE id = 1 AND claim_token = $1`,
        [token, JSON.stringify(pending), delay, pollInterval, remaining, inserted, Math.floor(observedAt / sizeMs) * sizeMs],
      )
      log(`Ingest ok: ${inserted} new of ${rows.length} events, ${failures.length} failed, ${spikes} spikes, next poll in ${delay}s`)
      await cleanup(db).catch((err) => log(`Cleanup skipped: ${String(err)}`))
      return { result: 'ok', inserted, fetched: rows.length, failed: failures.length }
    } catch (err) {
      const limited = err instanceof RateLimited
      const streak = held.failures + 1
      const wait = limited ? err.waitSeconds : backoffSeconds(cfg, streak)
      const message = String((err as Error).message ?? err).slice(0, 500)
      await db(
        `UPDATE ingestion_state SET
            next_poll_at = now() + make_interval(secs => $2::int), claim_token = NULL, claim_expires_at = NULL,
            status = $3, last_error = $4, consecutive_failures = consecutive_failures + 1,
            rate_limit_remaining = coalesce($5::int, rate_limit_remaining)
          WHERE id = 1 AND claim_token = $1`,
        [token, wait, limited ? 'RATE_LIMITED' : streak >= 3 ? 'FAILING' : 'DEGRADED', message, limited ? err.remaining : null],
      )
      log(`Ingest failed (streak ${streak}), retry in ${wait}s: ${message}`)
      return { result: 'failed', error: message }
    }
  } catch (err) {
    // The database itself failed (including while releasing the claim). The claim expires on its own.
    log(`Ingest aborted, claim ${held ? 'will expire' : 'not taken'}: ${String(err)}`)
    return { result: 'failed', error: String(err) }
  }
}
