import { API_BASE } from './config'
import type {
  Activity,
  Breakdown,
  FeedEvent,
  Metrics,
  Mode,
  RangeCode,
  Rising,
  SystemStatus,
  Trending,
} from './types'

async function get<T>(path: string, params: Record<string, string | number>): Promise<T> {
  const qs = new URLSearchParams(Object.entries(params).map(([k, v]) => [k, String(v)]))
  const res = await fetch(`${API_BASE}${path}?${qs}`, { headers: { Accept: 'application/json' } })
  if (!res.ok) throw new Error(`${path} responded ${res.status}`)
  return (await res.json()) as T
}

export const api = {
  metrics: (mode: Mode, range: RangeCode) => get<Metrics>('/api/metrics', { mode, range }),
  activity: (mode: Mode, range: RangeCode) => get<Activity>('/api/activity', { mode, range }),
  trending: (mode: Mode, range: RangeCode) => get<Trending>('/api/repositories/trending', { mode, range }),
  breakdown: (mode: Mode, range: RangeCode) => get<Breakdown>('/api/activity/breakdown', { mode, range }),
  rising: (mode: Mode) => get<Rising>('/api/repositories/rising', { mode }),
  events: async (mode: Mode, limit = 100) =>
    (await get<{ events: FeedEvent[] }>('/api/events/recent', { mode, limit })).events,
  status: (mode: Mode) => get<SystemStatus>('/api/system/status', { mode }),
  startDemo: async () => {
    const res = await fetch(`${API_BASE}/api/demo/start`, { method: 'POST' })
    if (!res.ok) throw new Error(`demo start responded ${res.status}`)
  },
}
