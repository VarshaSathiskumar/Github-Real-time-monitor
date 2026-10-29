// Ports of the analytics service value types (EventKind, ActivityCounts, Range) and the pulse.* settings.

export type Kind = 'PUSH' | 'PULL_REQUEST' | 'FORK' | 'WATCH' | 'ISSUES' | 'ISSUE_COMMENT' | 'OTHER'

/** Declaration order matters: the breakdown lists kinds in this order. */
export const KINDS: { kind: Kind; label: string }[] = [
  { kind: 'PUSH', label: 'Push events' },
  { kind: 'PULL_REQUEST', label: 'Pull requests' },
  { kind: 'FORK', label: 'Forks' },
  { kind: 'WATCH', label: 'Stars' },
  { kind: 'ISSUES', label: 'Issues' },
  { kind: 'ISSUE_COMMENT', label: 'Issue comments' },
  { kind: 'OTHER', label: 'Other events' },
]

export function kindOf(githubType: string): Kind {
  switch (githubType) {
    case 'PushEvent':
      return 'PUSH'
    case 'PullRequestEvent':
      return 'PULL_REQUEST'
    case 'ForkEvent':
      return 'FORK'
    case 'WatchEvent':
      return 'WATCH'
    case 'IssuesEvent':
      return 'ISSUES'
    case 'IssueCommentEvent':
      return 'ISSUE_COMMENT'
    default:
      return 'OTHER'
  }
}

/** Additive event counters, the same ten columns as the aggregate tables. */
export interface Counts {
  total: number
  push: number
  pullRequest: number
  fork: number
  watch: number
  issues: number
  issueComments: number
  other: number
  commits: number
  commitReports: number
}

export const ZERO: Counts = {
  total: 0,
  push: 0,
  pullRequest: 0,
  fork: 0,
  watch: 0,
  issues: 0,
  issueComments: 0,
  other: 0,
  commits: 0,
  commitReports: 0,
}

export function countsOf(kind: Kind, commits: number | null): Counts {
  const push = kind === 'PUSH'
  return {
    total: 1,
    push: push ? 1 : 0,
    pullRequest: kind === 'PULL_REQUEST' ? 1 : 0,
    fork: kind === 'FORK' ? 1 : 0,
    watch: kind === 'WATCH' ? 1 : 0,
    issues: kind === 'ISSUES' ? 1 : 0,
    issueComments: kind === 'ISSUE_COMMENT' ? 1 : 0,
    other: kind === 'OTHER' ? 1 : 0,
    commits: push && commits !== null ? commits : 0,
    commitReports: push && commits !== null ? 1 : 0,
  }
}

export function plus(a: Counts, b: Counts): Counts {
  return {
    total: a.total + b.total,
    push: a.push + b.push,
    pullRequest: a.pullRequest + b.pullRequest,
    fork: a.fork + b.fork,
    watch: a.watch + b.watch,
    issues: a.issues + b.issues,
    issueComments: a.issueComments + b.issueComments,
    other: a.other + b.other,
    commits: a.commits + b.commits,
    commitReports: a.commitReports + b.commitReports,
  }
}

export function countOf(c: Counts, kind: Kind): number {
  switch (kind) {
    case 'PUSH':
      return c.push
    case 'PULL_REQUEST':
      return c.pullRequest
    case 'FORK':
      return c.fork
    case 'WATCH':
      return c.watch
    case 'ISSUES':
      return c.issues
    case 'ISSUE_COMMENT':
      return c.issueComments
    case 'OTHER':
      return c.other
  }
}

/** Everything that is not a push, pull request or fork. */
export function otherForScore(c: Counts): number {
  return c.total - c.push - c.pullRequest - c.fork
}

export interface Score {
  push: number
  pullRequest: number
  fork: number
  other: number
}

export function score(c: Counts, w: Score): number {
  return c.push * w.push + c.pullRequest * w.pullRequest + c.fork * w.fork + otherForScore(c) * w.other
}

export function compact(c: Counts): string {
  return [c.total, c.push, c.pullRequest, c.fork, c.watch, c.issues, c.issueComments, c.other, c.commits, c.commitReports].join(',')
}

export interface Range {
  code: '15m' | '1h' | '6h' | '24h'
  minutes: number
  bucketMinutes: number
}

export const RANGES: Range[] = [
  { code: '15m', minutes: 15, bucketMinutes: 1 },
  { code: '1h', minutes: 60, bucketMinutes: 1 },
  { code: '6h', minutes: 360, bucketMinutes: 5 },
  { code: '24h', minutes: 1440, bucketMinutes: 15 },
]

export function parseRange(code: string | null): Range | undefined {
  if (code === null || code.trim() === '') return RANGES[1]
  return RANGES.find((r) => r.code === code.trim().toLowerCase())
}

export const sparklineBucketMinutes = (r: Range): number => Math.max(1, Math.ceil(r.minutes / 30))

export const MINUTE = 60_000

/** Java Instant.toString for whole seconds, the format of every timestamp on the wire. */
export const iso = (ms: number): string => new Date(ms).toISOString().replace('.000Z', 'Z')

/** Momentum detector settings, the pulse.momentum.* block of the analytics service. */
export interface MomentumConfig {
  windowMinutes: number
  baselineWindows: number
  minHistoryWindows: number
  minEvents: number
  spikeRatio: number
  minBaseline: number
  visibleForMinutes: number
}

function num(env: string | undefined, fallback: number): number {
  const n = env === undefined || env.trim() === '' ? NaN : Number(env)
  return Number.isFinite(n) ? n : fallback
}

/** Same variable names and defaults as application.yml. */
export function settingsFromEnv(env: Record<string, string | undefined> = process.env): {
  score: Score
  momentum: MomentumConfig
} {
  return {
    score: {
      push: num(env.SCORE_WEIGHT_PUSH, 2),
      pullRequest: num(env.SCORE_WEIGHT_PULL_REQUEST, 3),
      fork: num(env.SCORE_WEIGHT_FORK, 2),
      other: num(env.SCORE_WEIGHT_OTHER, 1),
    },
    momentum: {
      windowMinutes: 5,
      baselineWindows: 6,
      minHistoryWindows: 3,
      minEvents: num(env.MOMENTUM_MIN_EVENTS, 8),
      spikeRatio: 3.0,
      minBaseline: 1.0,
      visibleForMinutes: 15,
    },
  }
}
