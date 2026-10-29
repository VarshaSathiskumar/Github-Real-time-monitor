import type { Snapshot, SystemStatus } from '../../src/lib/types.js'
import { demoSource } from './demo.js'
import { type Deps, maybeIngest } from './ingest.js'
import { type MomentumConfig, type Range, type Score, iso, parseRange, settingsFromEnv } from './model.js'
import { type Db, ingestionDue, liveSource, readIngestion } from './postgres.js'
import { type DashboardSource, activity, breakdown, metrics, rising, trending } from './views.js'
import { neonDb } from './db.js'

// One request returns every dashboard view, so a visitor costs one function invocation per refresh
// instead of seven.

const DISCLAIMER =
  "Events are near real time. They come from GitHub's public events API, which is a sampled, " +
  'delayed and rate-limited view of platform activity, so counts are not global totals.'

export async function buildSnapshot(
  src: DashboardSource,
  range: Range,
  settings: { score: Score; momentum: MomentumConfig },
  status: SystemStatus,
): Promise<Snapshot> {
  const [m, a, t, b, r, events] = await Promise.all([
    metrics(src, range),
    activity(src, range),
    trending(src, range, settings.score),
    breakdown(src, range),
    rising(src, settings.momentum),
    src.recentEvents(100),
  ])
  return { mode: src.mode, range: range.code, metrics: m, activity: a, trending: t, breakdown: b, rising: r, events, status, generatedAt: new Date().toISOString() }
}

const demoStatus = (): SystemStatus => ({
  mode: 'demo',
  architecture: 'serverless-lite',
  ingestion: {
    reachable: false, status: 'STOPPED', eventsIngested: 0, consecutiveFailures: 0, pollIntervalSeconds: 0,
    rateLimitRemaining: -1,
  },
  analytics: { streamsState: 'SERVERLESS', redis: false, postgres: false },
  demo: { running: true },
  disclaimer: DISCLAIMER,
  generatedAt: new Date().toISOString(),
})

async function liveStatus(db: Db): Promise<{ status: SystemStatus; due: boolean }> {
  const s = await readIngestion(db)
  const status: SystemStatus = {
    mode: 'live',
    architecture: 'serverless-lite',
    ingestion: {
      reachable: s !== null,
      status: s?.status ?? 'STARTING',
      lastSuccessfulPoll: s?.lastSuccessfulPoll === null || !s ? undefined : iso(s.lastSuccessfulPoll),
      lastPollAttempt: s?.lastPollAttempt === null || !s ? undefined : iso(s.lastPollAttempt),
      eventsIngested: s?.eventsIngested ?? 0,
      lastError: s?.lastError || undefined,
      consecutiveFailures: s?.consecutiveFailures ?? 0,
      pollIntervalSeconds: s?.pollIntervalSeconds ?? 0,
      rateLimitRemaining: s?.rateLimitRemaining ?? -1,
    },
    analytics: {
      streamsState: 'SERVERLESS',
      redis: false,
      postgres: true,
      lastEventObservedAt: s?.lastObservedAt === null || !s ? undefined : iso(s.lastObservedAt),
    },
    demo: { running: false },
    disclaimer: DISCLAIMER,
    generatedAt: new Date().toISOString(),
  }
  return { status, due: s !== null && ingestionDue(s) }
}

export interface HandlerDeps {
  db?: () => Db
  /** Keeps the function alive after the response, so ingestion never delays or breaks a dashboard request. */
  waitUntil: (p: Promise<unknown>) => void
  ingest?: Partial<Deps>
  now?: () => number
  /** How long a built snapshot is reused inside one function instance. */
  memoMs?: number
}

const memo = new Map<string, { at: number; value: Promise<Snapshot> }>()

/** For tests: forget every built snapshot. */
export const clearSnapshotMemo = (): void => memo.clear()

// 10 s at the CDN keeps refreshes of one open tab cheap; 5 s in the instance covers cache-busting query strings.
const CDN_CACHE = 'public, max-age=0, s-maxage=10'

/** Strips nulls like the Spring services do (JSON omits nulls, the frontend treats missing as null). */
const json = (body: unknown, status: number, cache: string): Response =>
  new Response(JSON.stringify(body, (_k, v: unknown) => (v === null ? undefined : v)), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': cache },
  })

export async function handleSnapshot(request: Request, deps: HandlerDeps): Promise<Response> {
  const url = new URL(request.url)
  const mode = url.searchParams.get('mode') ?? 'live'
  const range = parseRange(url.searchParams.get('range'))
  if ((mode !== 'live' && mode !== 'demo') || !range) {
    return json({ error: "mode must be live or demo, range one of 15m, 1h, 6h, 24h" }, 400, 'no-store')
  }
  const settings = settingsFromEnv()
  const now = deps.now ?? Date.now

  try {
    if (mode === 'demo') {
      return json(await buildSnapshot(demoSource(now(), settings.momentum), range, settings, demoStatus()), 200, CDN_CACHE)
    }

    const key = `${mode}:${range.code}`
    const hit = memo.get(key)
    if (hit && now() - hit.at >= 0 && now() - hit.at < (deps.memoMs ?? 5_000)) return json(await hit.value, 200, CDN_CACHE)

    const db = (deps.db ?? neonDb)()
    const value = (async () => {
      const { status, due } = await liveStatus(db)
      const snapshot = await buildSnapshot(liveSource(db, settings.momentum, now), range, settings, status)
      if (due) {
        deps.waitUntil(maybeIngest({ db, momentum: settings.momentum, now, ...deps.ingest }))
      }
      return snapshot
    })()
    memo.set(key, { at: now(), value })
    value.catch(() => memo.delete(key))
    return json(await value, 200, CDN_CACHE)
  } catch (err) {
    console.error('snapshot failed', err)
    return json({ error: 'Dashboard data is temporarily unavailable' }, 503, 'no-store')
  }
}
