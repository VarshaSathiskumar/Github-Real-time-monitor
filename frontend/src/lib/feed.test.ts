import { describe, expect, it } from 'vitest'
import { mergeEvents } from './feed'
import type { FeedEvent } from './types'

const ev = (id: string): FeedEvent => ({
  id,
  kind: 'PUSH',
  type: 'PushEvent',
  actor: 'a',
  repo: 'o/r',
  repoUrl: 'https://github.com/o/r',
  description: 'd',
  url: 'u',
  createdAt: '2026-01-01T00:00:00Z',
  observedAt: '2026-01-01T00:00:00Z',
  demo: false,
})

describe('mergeEvents', () => {
  it('puts incoming events (newest first) in front', () => {
    const merged = mergeEvents([ev('1'), ev('2')], [ev('4'), ev('3')])
    expect(merged.map((e) => e.id)).toEqual(['4', '3', '1', '2'])
  })

  it('drops duplicates delivered twice', () => {
    const merged = mergeEvents([ev('1'), ev('2')], [ev('2'), ev('3')])
    expect(merged.map((e) => e.id)).toEqual(['2', '3', '1'])
  })

  it('caps the buffer', () => {
    const merged = mergeEvents([ev('1'), ev('2'), ev('3')], [ev('4')], 2)
    expect(merged.map((e) => e.id)).toEqual(['4', '1'])
  })

  it('returns the same list when nothing arrives', () => {
    const current = [ev('1')]
    expect(mergeEvents(current, [])).toBe(current)
  })
})
