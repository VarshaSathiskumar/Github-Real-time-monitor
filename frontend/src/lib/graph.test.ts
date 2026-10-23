import { describe, expect, it } from 'vitest'
import { devId, eventId, GraphStore, repoId, visibleGraph } from './graph'
import type { FeedEvent } from './types'

const ev = (id: string, actor = 'alice', repo = 'o/r'): FeedEvent => ({
  id,
  kind: 'PUSH',
  type: 'PushEvent',
  actor,
  repo,
  repoUrl: `https://github.com/${repo}`,
  description: `${actor} pushed to ${repo}`,
  url: '',
  createdAt: '2026-01-01T00:00:00Z',
  observedAt: '2026-01-01T00:00:00Z',
  demo: false,
})

const none = new Set<string>()

describe('GraphStore', () => {
  it('links developers, repositories and events', () => {
    const s = new GraphStore()
    s.ingest([ev('1'), ev('2', 'bob')])
    expect(s.repos.size).toBe(1)
    expect(s.devs.size).toBe(2)
    expect(s.events.size).toBe(2)
    expect(s.repos.get(repoId('o/r'))?.events).toBe(2)
    expect(s.developersOf(repoId('o/r')).map((d) => d.dev.login).sort()).toEqual(['alice', 'bob'])
    expect(s.repositoriesOf(devId('alice')).map((r) => r.repo.name)).toEqual(['o/r'])
  })

  it('ignores an event id it has already seen', () => {
    const s = new GraphStore()
    expect(s.ingest([ev('1')])).toBe(1)
    expect(s.ingest([ev('1'), ev('1')])).toBe(0)
    expect(s.repos.get(repoId('o/r'))?.events).toBe(1)
  })

  it('treats repository and login case as the same node', () => {
    const s = new GraphStore()
    s.ingest([ev('1', 'Alice', 'Org/Repo'), ev('2', 'alice', 'org/repo')])
    expect(s.repos.size).toBe(1)
    expect(s.devs.size).toBe(1)
  })

  it('skips malformed events', () => {
    const s = new GraphStore()
    expect(s.ingest([{ ...ev('1'), actor: '' }, { ...ev('2'), repo: '' }])).toBe(0)
    expect(s.isEmpty).toBe(true)
  })

  it('evicts the least recently active repository with its events and orphaned developers', () => {
    const s = new GraphStore({ repos: 2, devs: 10, events: 10 })
    s.ingest([ev('1', 'a', 'o/one'), ev('2', 'b', 'o/two'), ev('3', 'c', 'o/three')])
    expect(s.repos.has(repoId('o/one'))).toBe(false)
    expect(s.devs.has(devId('a'))).toBe(false)
    expect(s.events.has(eventId('1'))).toBe(false)
    expect(s.repos.size).toBe(2)
    expect(s.devs.size).toBe(2)
  })

  it('keeps a developer that still has another repository', () => {
    const s = new GraphStore({ repos: 1, devs: 10, events: 10 })
    s.ingest([ev('1', 'a', 'o/one'), ev('2', 'a', 'o/two')])
    expect(s.repos.size).toBe(1)
    expect(s.devs.has(devId('a'))).toBe(true)
  })

  it('drops the oldest events beyond the limit and never re-adds them', () => {
    const s = new GraphStore({ repos: 10, devs: 10, events: 2 })
    s.ingest([ev('1'), ev('2'), ev('3')])
    expect([...s.events.keys()]).toEqual([eventId('2'), eventId('3')])
    expect(s.ingest([ev('1')])).toBe(0)
  })

  it('clears everything when the mode changes', () => {
    const s = new GraphStore()
    s.ingest([ev('1')])
    s.clear('demo')
    expect(s.mode).toBe('demo')
    expect(s.isEmpty).toBe(true)
    expect(s.ingest([ev('1')])).toBe(1)
  })
})

describe('visibleGraph', () => {
  const build = () => {
    const s = new GraphStore()
    s.ingest([ev('1', 'a', 'o/one'), ev('2', 'b', 'o/two'), ev('3', 'c', 'o/two')])
    return s
  }

  it('always draws repositories, developers and the freshest events', () => {
    const g = visibleGraph(build(), { expanded: none, liveEvents: 1 })
    expect(g.nodes.filter((n) => n.type === 'repo')).toHaveLength(2)
    expect(g.nodes.filter((n) => n.type === 'dev')).toHaveLength(3)
    expect(g.nodes.filter((n) => n.type === 'event').map((n) => n.id)).toEqual([eventId('3')])
  })

  it('reveals the events of an expanded repository', () => {
    const g = visibleGraph(build(), { expanded: new Set([repoId('o/two')]), liveEvents: 0 })
    expect(g.nodes.filter((n) => n.type === 'event').map((n) => n.id).sort()).toEqual([eventId('2'), eventId('3')])
  })

  it('reveals the events of the selected developer', () => {
    const g = visibleGraph(build(), { expanded: none, selectedId: devId('a'), liveEvents: 0 })
    expect(g.nodes.filter((n) => n.type === 'event').map((n) => n.id)).toEqual([eventId('1')])
  })

  it('only emits edges whose endpoints exist and with unique ids', () => {
    const g = visibleGraph(build(), { expanded: none })
    const ids = new Set(g.nodes.map((n) => n.id))
    expect(g.edges.every((e) => ids.has(e.source) && ids.has(e.target))).toBe(true)
    expect(new Set(g.edges.map((e) => e.id)).size).toBe(g.edges.length)
  })

  it('weights the developer link by the number of events', () => {
    const s = new GraphStore()
    s.ingest([ev('1'), ev('2'), ev('3')])
    const link = visibleGraph(s, { expanded: none }).edges.find((e) => e.kind === 'dev-repo')
    expect(link?.weight).toBe(3)
  })
})
