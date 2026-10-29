import type { FeedEvent, RisingRepo } from '../../src/lib/types.js'
import { BURST_NOTE, SPIKE_NOTE } from './momentum.js'
import { type Counts, type MomentumConfig, MINUTE, iso } from './model.js'
import type { ActivityStore, DashboardSource, MinuteRow } from './views.js'

/** Runs one parameterised statement and returns its rows. Neon over HTTP in production, PGlite in tests. */
export type Db = (text: string, params?: unknown[]) => Promise<Record<string, unknown>[]>

const COLUMNS = 'total, push, pull_request, fork, watch, issues, issue_comments, other, commits, commit_reports'
const SUMS =
  'sum(total), sum(push), sum(pull_request), sum(fork), sum(watch), sum(issues), sum(issue_comments), sum(other), sum(commits), sum(commit_reports)'
const COUNT_FIELDS = [
  'total', 'push', 'pull_request', 'fork', 'watch', 'issues', 'issue_comments', 'other', 'commits', 'commit_reports',
] as const

/** Timestamps are always compared as epoch millis parameters, so no driver specific date handling is needed. */
const ts = (n: string): string => `to_timestamp(${n}::double precision / 1000)`

/** Millis from whatever the driver returns for a timestamptz. */
export const ms = (v: unknown): number => new Date(v as string | number | Date).getTime()

const n = (v: unknown): number => Number(v ?? 0)

function counts(r: Record<string, unknown>, sums = false): Counts {
  const v = COUNT_FIELDS.map((f, i) => n(sums ? r[`s${i}`] : r[f]))
  return {
    total: v[0], push: v[1], pullRequest: v[2], fork: v[3], watch: v[4], issues: v[5], issueComments: v[6],
    other: v[7], commits: v[8], commitReports: v[9],
  }
}

const sumsAliased = SUMS.split(', ').map((s, i) => `${s} AS s${i}`).join(', ')

/** Historical and live windows served from the minute and repository aggregate tables. */
export class PostgresStore implements ActivityStore {
  // Identical reads inside one snapshot (metrics, breakdown and trending share windows) run once.
  private readonly memo = new Map<string, Promise<unknown>>()

  constructor(private readonly db: Db) {}

  private once<T>(key: string, load: () => Promise<T>): Promise<T> {
    let p = this.memo.get(key) as Promise<T> | undefined
    if (!p) this.memo.set(key, (p = load()))
    return p
  }

  minuteCounts(from: number, to: number): Promise<MinuteRow[]> {
    return this.once(`min:${from}:${to}`, async () => {
      const rows = await this.db(
        `SELECT (extract(epoch from minute) * 1000)::bigint AS t, ${COLUMNS} FROM minute_aggregates
         WHERE minute >= ${ts('$1')} AND minute < ${ts('$2')} ORDER BY minute`,
        [from, to],
      )
      return rows.map((r) => ({ t: n(r.t), c: counts(r) }))
    })
  }

  activeRepositories(from: number, to: number): Promise<number> {
    return this.once(`active:${from}:${to}`, async () => {
      const rows = await this.db(
        `SELECT count(DISTINCT repo_name) AS n FROM repo_minute_aggregates
         WHERE minute >= ${ts('$1')} AND minute < ${ts('$2')}`,
        [from, to],
      )
      return n(rows[0]?.n)
    })
  }

  activeRepositorySeries(from: number, to: number, bucketMinutes: number): Promise<number[]> {
    return this.once(`series:${from}:${to}:${bucketMinutes}`, async () => {
      const first = Math.floor(Math.floor(from / 60_000) / bucketMinutes) * bucketMinutes
      const last = Math.floor(Math.floor((to - 1000) / 1000) / 60)
      const rows = await this.db(
        `SELECT (floor(extract(epoch from minute) / 60 / $3::int) * $3::int)::bigint AS b, count(DISTINCT repo_name) AS n
         FROM repo_minute_aggregates WHERE minute >= ${ts('$1')} AND minute < ${ts('$2')} GROUP BY b`,
        [from, to, bucketMinutes],
      )
      const byBucket = new Map(rows.map((r) => [n(r.b), n(r.n)]))
      const out: number[] = []
      for (let b = first; b <= last; b += bucketMinutes) out.push(byBucket.get(b) ?? 0)
      return out
    })
  }

  repositoryTotals(from: number, to: number, limit: number): Promise<Map<string, Counts>> {
    return this.once(`totals:${from}:${to}:${limit}`, async () => {
      const rows = await this.db(
        `SELECT repo_name, ${sumsAliased} FROM repo_minute_aggregates
         WHERE minute >= ${ts('$1')} AND minute < ${ts('$2')}
         GROUP BY repo_name ORDER BY sum(total) DESC, repo_name LIMIT $3`,
        [from, to, limit],
      )
      return new Map(rows.map((r) => [String(r.repo_name), counts(r, true)]))
    })
  }

  repositoryMinutes(repos: string[], from: number, to: number): Promise<Map<string, MinuteRow[]>> {
    const out = new Map<string, MinuteRow[]>(repos.map((r) => [r, []]))
    if (repos.length === 0) return Promise.resolve(out)
    return this.once(`repoMin:${repos.join('|')}:${from}:${to}`, async () => {
      const rows = await this.db(
        `SELECT repo_name, (extract(epoch from minute) * 1000)::bigint AS t, ${COLUMNS} FROM repo_minute_aggregates
         WHERE repo_name IN (SELECT jsonb_array_elements_text($1::jsonb))
           AND minute >= ${ts('$2')} AND minute < ${ts('$3')} ORDER BY minute`,
        [JSON.stringify(repos), from, to],
      )
      for (const r of rows) out.get(String(r.repo_name))?.push({ t: n(r.t), c: counts(r) })
      return out
    })
  }
}

export interface IngestionRow {
  status: string
  nextPollAt: number
  claimExpiresAt: number | null
  dbNow: number
  lastPollAttempt: number | null
  lastSuccessfulPoll: number | null
  eventsIngested: number
  lastError: string
  consecutiveFailures: number
  pollIntervalSeconds: number
  rateLimitRemaining: number
  lastObservedAt: number | null
}

const opt = (v: unknown): number | null => (v === null || v === undefined ? null : ms(v))

export async function readIngestion(db: Db): Promise<IngestionRow | null> {
  const rows = await db(
    `SELECT s.*, now() AS db_now, (SELECT max(observed_at) FROM github_events) AS last_observed
     FROM ingestion_state s WHERE s.id = 1`,
  )
  const r = rows[0]
  if (!r) return null
  return {
    status: String(r.status),
    nextPollAt: ms(r.next_poll_at),
    claimExpiresAt: opt(r.claim_expires_at),
    dbNow: ms(r.db_now),
    lastPollAttempt: opt(r.last_poll_attempt),
    lastSuccessfulPoll: opt(r.last_successful_poll),
    eventsIngested: n(r.events_ingested),
    lastError: String(r.last_error ?? ''),
    consecutiveFailures: n(r.consecutive_failures),
    pollIntervalSeconds: n(r.poll_interval_seconds),
    rateLimitRemaining: n(r.rate_limit_remaining),
    lastObservedAt: opt(r.last_observed),
  }
}

/** True when a visitor request may try to start a poll: it is due and no live claim is held. */
export const ingestionDue = (s: IngestionRow): boolean =>
  s.nextPollAt <= s.dbNow && (s.claimExpiresAt === null || s.claimExpiresAt <= s.dbNow)

/** The live data universe: everything is read from PostgreSQL (there is no Redis in the lite deployment). */
export function liveSource(db: Db, momentum: MomentumConfig, now: () => number = Date.now): DashboardSource {
  const store = new PostgresStore(db)
  return {
    mode: 'live',
    now,
    store,
    async observationStart() {
      const rows = await db(`SELECT (extract(epoch from min(minute)) * 1000)::bigint AS t FROM minute_aggregates`)
      return rows[0]?.t === null || rows[0]?.t === undefined ? null : n(rows[0].t)
    },
    async recentEvents(limit): Promise<FeedEvent[]> {
      const rows = await db(
        `SELECT event_id, kind, event_type, actor_login, repo_name, description, url, created_at, observed_at
         FROM github_events ORDER BY observed_at DESC, event_id DESC LIMIT $1`,
        [limit],
      )
      return rows.map((r) => ({
        id: String(r.event_id),
        kind: String(r.kind) as FeedEvent['kind'],
        type: String(r.event_type),
        actor: String(r.actor_login),
        repo: String(r.repo_name),
        repoUrl: `https://github.com/${r.repo_name}`,
        description: String(r.description),
        url: String(r.url),
        createdAt: iso(ms(r.created_at)),
        observedAt: iso(ms(r.observed_at)),
        demo: false,
      }))
    },
    async rising(): Promise<RisingRepo[]> {
      const cutoff = now() - momentum.visibleForMinutes * MINUTE
      // The latest detection per repository, like the Redis hash keyed by repository.
      const rows = await db(
        `SELECT DISTINCT ON (repo_name) repo_name, (extract(epoch from window_start) * 1000)::bigint AS ws,
                window_minutes, current_count, baseline, pct_increase, level, detected_at
         FROM rising_repositories WHERE window_start >= ${ts('$1')} ORDER BY repo_name, detected_at DESC`,
        [cutoff],
      )
      return rows
        .map((r): RisingRepo => ({
          repo: String(r.repo_name),
          repoUrl: `https://github.com/${r.repo_name}`,
          windowStart: iso(n(r.ws)),
          windowMinutes: n(r.window_minutes),
          currentCount: n(r.current_count),
          baseline: n(r.baseline),
          pctIncrease: r.pct_increase === null ? null : n(r.pct_increase),
          level: String(r.level) as RisingRepo['level'],
          note: r.level === 'BURST' ? BURST_NOTE : SPIKE_NOTE,
          detectedAt: iso(ms(r.detected_at)),
        }))
        .sort((a, b) => b.currentCount - a.currentCount)
        .slice(0, 10)
    },
  }
}
