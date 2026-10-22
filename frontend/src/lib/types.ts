export type Mode = 'live' | 'demo'
export type RangeCode = '15m' | '1h' | '6h' | '24h'

export type EventKind =
  | 'PUSH'
  | 'PULL_REQUEST'
  | 'FORK'
  | 'WATCH'
  | 'ISSUES'
  | 'ISSUE_COMMENT'
  | 'OTHER'

export interface Kpi {
  /** null when the metric is not available from the data source */
  value: number | null
  /** null when no comparable previous window exists */
  changePct: number | null
  sparkline: number[]
  note?: string | null
  /** what the change percentage compares, e.g. the previous 1h window */
  changeBasis?: string | null
}

export interface Metrics {
  mode: Mode
  range: RangeCode
  windowStart: string
  windowEnd: string
  totalEvents: Kpi
  activeRepositories: Kpi
  pushEvents: Kpi
  pullRequests: Kpi
  forks: Kpi
  generatedAt: string
}

export interface ActivityPoint {
  t: string
  total: number
  push: number
  pullRequest: number
}

export interface Activity {
  mode: Mode
  range: RangeCode
  bucketMinutes: number
  points: ActivityPoint[]
  generatedAt: string
}

export interface TrendingRepo {
  rank: number
  name: string
  owner: string
  url: string
  score: number
  share: number
  totalEvents: number
  pushEvents: number
  pullRequestEvents: number
  forkEvents: number
  otherEvents: number
  sparkline: number[]
}

export interface Trending {
  mode: Mode
  range: RangeCode
  scoreFormula: string
  disclaimer: string
  repositories: TrendingRepo[]
  generatedAt: string
}

export interface BreakdownItem {
  kind: EventKind
  label: string
  count: number
  percent: number
}

export interface Breakdown {
  mode: Mode
  range: RangeCode
  total: number
  items: BreakdownItem[]
  generatedAt: string
}

export interface RisingRepo {
  repo: string
  repoUrl: string
  windowStart: string
  windowMinutes: number
  currentCount: number
  baseline: number
  pctIncrease: number | null
  level: 'BURST' | 'WARMING' | 'RISING' | 'SURGING'
  note: string
  detectedAt: string
}

export interface Rising {
  mode: Mode
  windowMinutes: number
  baselineWindows: number
  minEvents: number
  disclaimer: string
  repositories: RisingRepo[]
  generatedAt: string
}

export interface FeedEvent {
  id: string
  kind: EventKind
  type: string
  actor: string
  repo: string
  repoUrl: string
  description: string
  url: string
  createdAt: string
  observedAt: string
  demo: boolean
}

export interface IngestionInfo {
  reachable: boolean
  status: string
  lastSuccessfulPoll?: string
  lastPollAttempt?: string
  eventsIngested: number
  lastError?: string
  consecutiveFailures: number
  pollIntervalSeconds: number
  rateLimitRemaining: number
}

export interface SystemStatus {
  mode: Mode
  ingestion: IngestionInfo
  analytics: {
    streamsState: string
    redis: boolean
    postgres: boolean
    consumerLag?: number
    lastEventObservedAt?: string
  }
  demo: { running: boolean; startedAt?: string }
  disclaimer: string
  generatedAt: string
}

export type ConnectionState = 'connecting' | 'open' | 'reconnecting'

export interface WsEnvelope {
  type: 'hello' | 'kpi' | 'activity' | 'trending' | 'breakdown' | 'rising' | 'events' | 'alert' | 'status'
  mode: Mode
  range?: RangeCode | null
  ts: string
  data: unknown
}
