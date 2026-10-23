import type { EventKind } from './types'

const full = new Intl.NumberFormat('en-US')
const compact = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 })

/** Full grouping up to 99,999, compact (1.2M) beyond. */
export function formatCount(n: number): string {
  return Math.abs(n) >= 100_000 ? compact.format(n) : full.format(Math.round(n))
}

export function formatPercent(n: number): string {
  const abs = Math.abs(n)
  return `${abs >= 100 ? Math.round(abs) : abs.toFixed(1)}%`
}

export function timeAgo(iso: string | number | Date | undefined | null, now: number = Date.now()): string {
  if (!iso) return 'never'
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return 'unknown'
  const s = Math.max(0, Math.round((now - t) / 1000))
  if (s < 5) return 'just now'
  if (s < 60) return `${s}s ago`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ago`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h ago`
  return `${Math.floor(h / 24)}d ago`
}

export function formatClock(iso: string | number | Date, withSeconds = false): string {
  return new Date(iso).toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    ...(withSeconds ? { second: '2-digit' } : {}),
  })
}

export const KIND_META: Record<EventKind, { label: string; short: string; color: string }> = {
  PUSH: { label: 'Push events', short: 'Push', color: '#2563eb' },
  PULL_REQUEST: { label: 'Pull requests', short: 'PR', color: '#7c3aed' },
  FORK: { label: 'Forks', short: 'Fork', color: '#059669' },
  WATCH: { label: 'Stars', short: 'Star', color: '#d97706' },
  ISSUES: { label: 'Issues', short: 'Issue', color: '#e11d48' },
  ISSUE_COMMENT: { label: 'Issue comments', short: 'Comment', color: '#0e7490' },
  OTHER: { label: 'Other events', short: 'Other', color: '#64748b' },
}
