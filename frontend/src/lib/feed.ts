import type { FeedEvent } from './types'

/** Number of events kept in memory so type filters still have something to show. */
export const FEED_BUFFER = 100
/** Number of rows displayed. */
export const FEED_VISIBLE = 20

/** Prepends incoming events (given newest first) and drops duplicates and overflow. */
export function mergeEvents(current: FeedEvent[], incoming: FeedEvent[], limit = FEED_BUFFER): FeedEvent[] {
  if (incoming.length === 0) return current
  const seen = new Set<string>()
  const out: FeedEvent[] = []
  for (const e of [...incoming, ...current]) {
    if (seen.has(e.id)) continue
    seen.add(e.id)
    out.push(e)
    if (out.length >= limit) break
  }
  return out
}
