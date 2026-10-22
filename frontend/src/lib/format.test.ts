import { describe, expect, it } from 'vitest'
import { formatCount, formatPercent, timeAgo } from './format'

describe('formatCount', () => {
  it('groups digits for ordinary values', () => {
    expect(formatCount(1234)).toBe('1,234')
    expect(formatCount(99999)).toBe('99,999')
  })
  it('switches to compact notation for large values', () => {
    expect(formatCount(1_250_000)).toBe('1.3M')
  })
})

describe('formatPercent', () => {
  it('keeps one decimal below 100 and none above', () => {
    expect(formatPercent(12.34)).toBe('12.3%')
    expect(formatPercent(-5)).toBe('5.0%')
    expect(formatPercent(450.6)).toBe('451%')
  })
})

describe('timeAgo', () => {
  const now = Date.parse('2026-01-01T12:00:00Z')
  it('describes recent times', () => {
    expect(timeAgo('2026-01-01T11:59:58Z', now)).toBe('just now')
    expect(timeAgo('2026-01-01T11:59:30Z', now)).toBe('30s ago')
    expect(timeAgo('2026-01-01T11:55:00Z', now)).toBe('5m ago')
    expect(timeAgo('2026-01-01T09:00:00Z', now)).toBe('3h ago')
  })
  it('handles missing and invalid input', () => {
    expect(timeAgo(undefined, now)).toBe('never')
    expect(timeAgo('garbage', now)).toBe('unknown')
  })
})
