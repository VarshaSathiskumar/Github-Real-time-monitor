import type {
  Activity,
  Breakdown,
  FeedEvent,
  Kpi,
  Metrics,
  RisingRepo,
  Rising,
  Trending,
} from '../../src/lib/types.js'
import {
  type Counts,
  type MomentumConfig,
  type Range,
  type Score,
  KINDS,
  MINUTE,
  ZERO,
  countOf,
  iso,
  otherForScore,
  plus,
  score as scoreOf,
  sparklineBucketMinutes,
} from './model.js'
import { ownerOf } from './parser.js'

// Port of DashboardService, Buckets and the ActivityStore / DashboardSource read model.

export interface MinuteRow {
  /** start of the minute, epoch millis */
  t: number
  c: Counts
}

/** Read model over per-minute activity, implemented by PostgreSQL and the in-memory demo store. */
export interface ActivityStore {
  /** Non-empty minutes in [from, to), ascending. */
  minuteCounts(from: number, to: number): Promise<MinuteRow[]>
  activeRepositories(from: number, to: number): Promise<number>
  /** Distinct repositories per bucket of bucketMinutes, aligned to the epoch, dense and ascending. */
  activeRepositorySeries(from: number, to: number, bucketMinutes: number): Promise<number[]>
  /** Totals per repository in [from, to), at most limit repositories (the most active ones). */
  repositoryTotals(from: number, to: number, limit: number): Promise<Map<string, Counts>>
  repositoryMinutes(repos: string[], from: number, to: number): Promise<Map<string, MinuteRow[]>>
}

/** One data universe (live or demo) as seen by the dashboard. */
export interface DashboardSource {
  mode: 'live' | 'demo'
  now(): number
  store: ActivityStore
  /** First minute for which any data exists; decides whether a previous-window comparison is valid. */
  observationStart(): Promise<number | null>
  recentEvents(limit: number): Promise<FeedEvent[]>
  rising(): Promise<RisingRepo[]>
}

export interface Settings {
  score: Score
  momentum: MomentumConfig
}

export const TRENDING_DISCLAIMER =
  'Most active repositories in the observed GitHub event sample, ranked by activity score. ' +
  "This is not a ranking of GitHub's most popular repositories."
export const RISING_DISCLAIMER =
  "Activity spikes in the observed event sample compared with each repository's recent baseline. " +
  'They are not definitive global GitHub trends.'
export const COMMITS_UNAVAILABLE =
  "Not available: GitHub's public events API no longer reports commit counts in push events."

const TRENDING_LIMIT = 5
const TRENDING_SPARKLINE_BUCKETS = 12
/** Shortest comparison span (minutes) for which a change percentage is considered meaningful. */
const MIN_PARTIAL_SPAN_MINUTES = 3

const round = (v: number): number => Math.round(v * 10) / 10

// Buckets

export interface Bucket {
  start: number
  counts: Counts
}

/** Groups per-minute counts into epoch-aligned buckets of N minutes, filling empty buckets with zero. */
export function aggregate(minutes: MinuteRow[], from: number, to: number, bucketMinutes: number): Bucket[] {
  const first = Math.floor(Math.floor(from / 60_000) / bucketMinutes) * bucketMinutes
  const last = Math.floor(Math.floor((to - 1000) / 1000) / 60)
  const sums = new Map<number, Counts>()
  for (const m of minutes) {
    const minute = Math.floor(m.t / 60_000)
    if (minute < first || minute > last) continue
    const bucket = Math.floor(minute / bucketMinutes) * bucketMinutes
    sums.set(bucket, plus(sums.get(bucket) ?? ZERO, m.c))
  }
  const out: Bucket[] = []
  for (let b = first; b <= last; b += bucketMinutes) out.push({ start: b * 60_000, counts: sums.get(b) ?? ZERO })
  return out
}

export function sum(minutes: MinuteRow[]): Counts {
  return minutes.reduce((t, m) => plus(t, m.c), ZERO)
}

// Comparison window

export interface Comparison {
  available: boolean
  end: number
  /** span of each of the two compared windows, millis */
  span: number
  basis: string | null
}

/**
 * Two adjacent, equally long spans ending at end. With a full history these are the selected window and the
 * previous equivalent window. While less history exists the spans shrink to half of what has been observed.
 */
export function comparison(observationStart: number | null, range: Range, end: number): Comparison {
  if (observationStart === null) return { available: false, end, span: 0, basis: null }
  const obs = observationStart - MINUTE
  const rangeMs = range.minutes * MINUTE
  const prevStart = end - 2 * rangeMs
  const from = obs > prevStart ? obs : prevStart
  if (from <= prevStart || end - from >= 2 * rangeMs) {
    return { available: true, end, span: rangeMs, basis: `Compared with the previous ${range.code} window` }
  }
  const cmpEnd = end - MINUTE
  const half = Math.trunc(Math.trunc((cmpEnd - from) / MINUTE) / 2)
  if (half < MIN_PARTIAL_SPAN_MINUTES) return { available: false, end: cmpEnd, span: 0, basis: null }
  return {
    available: true,
    end: cmpEnd,
    span: half * MINUTE,
    basis: `Only ${half * 2} minutes of data observed so far: last ${half} min compared with the ${half} min before`,
  }
}

export const windowEnd = (src: DashboardSource): number => Math.floor(src.now() / MINUTE) * MINUTE + MINUTE

// Views

function kpi(current: number, cmpCurrent: number, cmpPrevious: number, cmp: Comparison, sparkline: number[]): Kpi {
  const change = cmp.available && cmpPrevious > 0 ? round(((cmpCurrent - cmpPrevious) * 100) / cmpPrevious) : null
  return { value: current, changePct: change, sparkline, note: null, changeBasis: change === null ? null : cmp.basis }
}

function commitsKpi(cur: Counts, cmpCur: Counts, cmpPrev: Counts, cmp: Comparison, sparkline: number[]): Kpi {
  if (cur.commitReports === 0) {
    return { value: null, changePct: null, sparkline: [], note: COMMITS_UNAVAILABLE, changeBasis: null }
  }
  const k = kpi(cur.commits, cmpCur.commits, cmpPrev.commits, cmp, sparkline)
  const comparable = cmpPrev.commitReports > 0 && cmpCur.commitReports > 0
  const note =
    cur.commitReports < cur.push
      ? `Based on ${cur.commitReports} of ${cur.push} push events that reported a commit count.`
      : null
  return { ...k, changePct: comparable ? k.changePct : null, changeBasis: comparable ? k.changeBasis : null, note }
}

const series = (buckets: Bucket[], f: (c: Counts) => number): number[] => buckets.map((b) => f(b.counts))

export async function metrics(src: DashboardSource, range: Range): Promise<Metrics> {
  const store = src.store
  const end = windowEnd(src)
  const start = end - range.minutes * MINUTE
  const cmp = comparison(await src.observationStart(), range, end)
  const mid = cmp.end - cmp.span
  const from = mid - cmp.span

  const [current, activeCur, cmpCurRows, cmpPrevRows, activeCmpCur, activeCmpPrev] = await Promise.all([
    store.minuteCounts(start, end),
    store.activeRepositories(start, end),
    cmp.available ? store.minuteCounts(mid, cmp.end) : Promise.resolve([]),
    cmp.available ? store.minuteCounts(from, mid) : Promise.resolve([]),
    cmp.available ? store.activeRepositories(mid, cmp.end) : Promise.resolve(0),
    cmp.available ? store.activeRepositories(from, mid) : Promise.resolve(0),
  ])
  const cur = sum(current)
  const cmpCur = sum(cmpCurRows)
  const cmpPrev = sum(cmpPrevRows)

  const sb = sparklineBucketMinutes(range)
  // Sparklines stop at the last complete minute so the minute in progress does not draw a false drop.
  const sparkEnd = end - MINUTE
  const buckets = aggregate(current, start, sparkEnd, sb)
  const activeSeries = await store.activeRepositorySeries(start, sparkEnd, sb)

  return {
    mode: src.mode,
    range: range.code,
    windowStart: iso(start),
    windowEnd: iso(end),
    totalEvents: kpi(cur.total, cmpCur.total, cmpPrev.total, cmp, series(buckets, (c) => c.total)),
    activeRepositories: kpi(activeCur, activeCmpCur, activeCmpPrev, cmp, activeSeries),
    pushEvents: kpi(cur.push, cmpCur.push, cmpPrev.push, cmp, series(buckets, (c) => c.push)),
    commits: commitsKpi(cur, cmpCur, cmpPrev, cmp, series(buckets, (c) => c.commits)),
    pullRequests: kpi(cur.pullRequest, cmpCur.pullRequest, cmpPrev.pullRequest, cmp, series(buckets, (c) => c.pullRequest)),
    forks: kpi(cur.fork, cmpCur.fork, cmpPrev.fork, cmp, series(buckets, (c) => c.fork)),
    generatedAt: new Date().toISOString(),
  } as Metrics
}

export async function activity(src: DashboardSource, range: Range): Promise<Activity> {
  // The minute in progress is incomplete and would draw a misleading drop, so charts stop at the last full minute.
  const end = Math.floor(src.now() / MINUTE) * MINUTE
  const start = end - range.minutes * MINUTE
  const b = range.bucketMinutes
  const [rows, obs] = await Promise.all([src.store.minuteCounts(start, end), src.observationStart()])

  const points: Activity['points'] = []
  if (obs !== null) {
    for (const bucket of aggregate(rows, start, end, b)) {
      const bucketEnd = bucket.start + b * MINUTE
      // Do not draw a flat zero line for time before anything was observed.
      if (bucketEnd < obs) continue
      // Edge buckets cover fewer than b minutes of the window; average over the minutes actually covered.
      const f = Math.max(bucket.start, start)
      const t = Math.min(bucketEnd, end)
      const minutes = Math.max(1, Math.trunc((t - f) / MINUTE))
      const c = bucket.counts
      points.push({
        t: iso(bucket.start),
        total: round(c.total / minutes),
        push: round(c.push / minutes),
        pullRequest: round(c.pullRequest / minutes),
      })
    }
  }
  return {
    mode: src.mode,
    range: range.code,
    bucketMinutes: b,
    points,
    generatedAt: new Date().toISOString(),
  }
}

const num = (v: number): string => String(v)

export async function trending(src: DashboardSource, range: Range, w: Score): Promise<Trending> {
  const end = windowEnd(src)
  const start = end - range.minutes * MINUTE

  const totals = await src.store.repositoryTotals(start, end, 200)
  const cmpStr = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0)
  const ranked = [...totals.entries()]
    .sort(([an, a], [bn, b]) => scoreOf(b, w) - scoreOf(a, w) || b.total - a.total || cmpStr(an, bn))
    .slice(0, TRENDING_LIMIT)

  const minutes = await src.store.repositoryMinutes(ranked.map(([n]) => n), start, end)
  const sb = Math.max(1, Math.ceil(range.minutes / TRENDING_SPARKLINE_BUCKETS))
  const top = ranked.length === 0 ? 1 : Math.max(1, scoreOf(ranked[0][1], w))

  const repositories = ranked.map(([name, c], i) => {
    const s = scoreOf(c, w)
    return {
      rank: i + 1,
      name,
      owner: ownerOf(name),
      url: `https://github.com/${name}`,
      score: round(s),
      share: round(s / top),
      totalEvents: c.total,
      pushEvents: c.push,
      pullRequestEvents: c.pullRequest,
      forkEvents: c.fork,
      otherEvents: otherForScore(c),
      sparkline: series(aggregate(minutes.get(name) ?? [], start, end, sb), (x) => x.total),
    }
  })
  return {
    mode: src.mode,
    range: range.code,
    scoreFormula: `Activity Score = Push x ${num(w.push)} + Pull Request x ${num(w.pullRequest)} + Fork x ${num(w.fork)} + Other x ${num(w.other)}`,
    weights: { push: w.push, pullRequest: w.pullRequest, fork: w.fork, other: w.other },
    disclaimer: TRENDING_DISCLAIMER,
    repositories,
    generatedAt: new Date().toISOString(),
  }
}

export async function breakdown(src: DashboardSource, range: Range): Promise<Breakdown> {
  const end = windowEnd(src)
  const start = end - range.minutes * MINUTE
  const c = sum(await src.store.minuteCounts(start, end))
  return {
    mode: src.mode,
    range: range.code,
    total: c.total,
    items: KINDS.map(({ kind, label }) => {
      const n = countOf(c, kind)
      return { kind, label, count: n, percent: c.total === 0 ? 0 : round((n * 100) / c.total) }
    }),
    generatedAt: new Date().toISOString(),
  }
}

export async function rising(src: DashboardSource, m: MomentumConfig): Promise<Rising> {
  return {
    mode: src.mode,
    windowMinutes: m.windowMinutes,
    baselineWindows: m.baselineWindows,
    minEvents: m.minEvents,
    disclaimer: RISING_DISCLAIMER,
    repositories: await src.rising(),
    generatedAt: new Date().toISOString(),
  }
}
