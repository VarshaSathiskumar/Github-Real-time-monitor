import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Snapshot } from '../../src/lib/types.js'
import { demoSource } from './demo.js'
import { ingestConfigFromEnv } from './ingest.js'
import { settingsFromEnv } from './model.js'
import { type HandlerDeps, clearSnapshotMemo, handleSnapshot } from './snapshot.js'
import { testDb } from './test-db.js'

const NOW = Date.parse('2026-03-10T12:07:30Z')
const { momentum } = settingsFromEnv({})
const req = (q = ''): Request => new Request(`https://pulse.example/api/snapshot${q}`)
const body = async (r: Response): Promise<Snapshot> => (await r.json()) as Snapshot

describe('demo mode', () => {
  it('is independent of the database and of GitHub', async () => {
    const calls: Promise<unknown>[] = []
    const res = await handleSnapshot(req('?mode=demo&range=1h'), {
      db: () => {
        throw new Error('the database must not be touched')
      },
      waitUntil: (p) => calls.push(p),
      now: () => NOW,
    })
    const s = await body(res)

    expect(res.status).toBe(200)
    expect(calls).toHaveLength(0) // no ingestion is started for demo data
    expect(s.mode).toBe('demo')
    expect(s.metrics.mode).toBe('demo')
    expect(s.metrics.totalEvents.value).toBeGreaterThan(100)
    expect(s.events).toHaveLength(100)
    expect(s.events.every((e) => e.demo && e.id.startsWith('demo-'))).toBe(true)
    expect(s.status.architecture).toBe('serverless-lite')
    expect(s.status.demo.running).toBe(true)
  })

  it('is a pure function of the clock, so every function instance shows the same picture', async () => {
    const deps: HandlerDeps = { waitUntil: () => undefined, now: () => NOW }
    const strip = (s: Snapshot): unknown => ({ ...s, generatedAt: 0, metrics: { ...s.metrics, generatedAt: 0 }, activity: { ...s.activity, generatedAt: 0 }, trending: { ...s.trending, generatedAt: 0 }, breakdown: { ...s.breakdown, generatedAt: 0 }, rising: { ...s.rising, generatedAt: 0 }, status: { ...s.status, generatedAt: 0 } })
    const a = await body(await handleSnapshot(req('?mode=demo&range=6h'), deps))
    const b = await body(await handleSnapshot(req('?mode=demo&range=6h'), deps))
    expect(strip(a)).toEqual(strip(b))
  })

  it('simulates activity spikes that the momentum rule picks up', async () => {
    // 90 seconds into a 4 minute slot a burst is running on one of the quieter repositories
    const slotStart = Math.ceil(Date.parse('2026-03-10T12:00:00Z') / 240_000) * 240_000
    const rising = await demoSource(slotStart + 95_000, momentum).rising()
    expect(rising.length).toBeGreaterThan(0)
    expect(rising[0].currentCount).toBeGreaterThanOrEqual(8)
    expect(rising[0].note).toContain('observed event sample')
  })

  it('keeps feed event ids stable between refreshes so the feed does not duplicate rows', async () => {
    const a = await demoSource(NOW, momentum).recentEvents(100)
    const b = await demoSource(NOW + 5_000, momentum).recentEvents(100)
    const ids = new Set(a.map((e) => e.id))
    expect(b.filter((e) => ids.has(e.id)).length).toBeGreaterThan(50)
  })
})

describe('request validation', () => {
  it.each(['?range=2h', '?mode=other', '?range=1h&mode=LIVE'])('rejects %s without touching anything', async (q) => {
    const res = await handleSnapshot(req(q), { waitUntil: () => undefined })
    expect(res.status).toBe(400)
    expect(res.headers.get('cache-control')).toBe('no-store')
  })
})

describe('live mode', () => {
  let ctx: Awaited<ReturnType<typeof testDb>>
  let pending: Promise<unknown>[]
  let fetchCalls: number
  let githubResponse: () => Response | Promise<Response>

  const deps = (over: Partial<HandlerDeps> = {}): HandlerDeps => ({
    db: () => ctx.db,
    waitUntil: (p) => pending.push(p),
    now: () => NOW,
    memoMs: 0,
    ingest: {
      log: () => undefined,
      config: { ...ingestConfigFromEnv({}), pages: 1 },
      fetch: (async () => {
        fetchCalls++
        return githubResponse()
      }) as typeof fetch,
    },
    ...over,
  })
  const event = (id: number, repo: string): object => ({
    id: String(id), type: 'PushEvent', actor: { login: `u${id}` }, repo: { id: id, name: repo },
    payload: { ref: 'refs/heads/main', size: 2 }, created_at: '2026-03-10T12:06:00Z',
  })

  beforeEach(async () => {
    clearSnapshotMemo()
    ctx = await testDb()
    pending = []
    fetchCalls = 0
    githubResponse = () => new Response(JSON.stringify([event(2, 'a/b'), event(1, 'c/d')]), { status: 200 })
  })
  afterEach(() => ctx.pg.close())

  it('answers from the database immediately and ingests after the response', async () => {
    const first = await handleSnapshot(req('?mode=live&range=1h'), deps())
    const s1 = await body(first)

    expect(first.status).toBe(200)
    expect(s1.metrics.totalEvents.value).toBe(0)
    expect(s1.events).toEqual([])
    expect(s1.status.ingestion.status).toBe('STARTING')
    expect(pending).toHaveLength(1)
    expect(fetchCalls).toBe(0) // the visitor did not wait for GitHub

    await Promise.all(pending)
    expect(fetchCalls).toBe(1)

    const s2 = await body(await handleSnapshot(req('?mode=live&range=1h'), deps({ now: () => NOW + 10_000 })))
    expect(s2.events.map((e) => e.id)).toEqual(['2', '1'])
    expect(s2.events.every((e) => !e.demo)).toBe(true)
    expect(s2.status.ingestion).toMatchObject({ status: 'HEALTHY', eventsIngested: 2 })
    expect(s2.status.architecture).toBe('serverless-lite')
    expect(s2.metrics.pushEvents.value).toBe(2)
    expect((s2.metrics as unknown as { commits: { value: number } }).commits.value).toBe(4)
    expect(s2.trending.repositories.map((r) => r.name).sort()).toEqual(['a/b', 'c/d'])
    // not due again yet: no second poll is started
    expect(pending).toHaveLength(1)
  })

  it('stays available when GitHub fails, and shows the degraded ingestion state', async () => {
    githubResponse = () => {
      throw new Error('github unreachable')
    }
    const res = await handleSnapshot(req('?mode=live'), deps())
    await Promise.all(pending)
    expect(res.status).toBe(200)

    const after = await handleSnapshot(req('?mode=live'), deps())
    const s = await body(after)
    expect(after.status).toBe(200)
    expect(s.status.ingestion).toMatchObject({ status: 'DEGRADED', consecutiveFailures: 1 })
    expect(s.status.ingestion.lastError).toContain('github unreachable')
    expect(pending).toHaveLength(1) // backing off: no retry from the second visitor
  })

  it('keeps serving stored data while ingestion is broken', async () => {
    await handleSnapshot(req('?mode=live'), deps())
    await Promise.all(pending)
    await ctx.pg.exec(`UPDATE ingestion_state SET next_poll_at = now() - interval '1 second'`)
    githubResponse = () => new Response('boom', { status: 502 })
    await handleSnapshot(req('?mode=live'), deps({ now: () => NOW + 60_000 }))
    await Promise.all(pending)

    const s = await body(await handleSnapshot(req('?mode=live'), deps({ now: () => NOW + 61_000 })))
    expect(s.events).toHaveLength(2)
    expect(s.status.ingestion.status).toBe('DEGRADED')
  })

  it('reports an unavailable database as 503 that is never cached', async () => {
    const res = await handleSnapshot(req('?mode=live'), deps({ db: () => { throw new Error('DATABASE_URL is not set') } }))
    expect(res.status).toBe(503)
    expect(res.headers.get('cache-control')).toBe('no-store')
    expect(await res.json()).toEqual({ error: 'Dashboard data is temporarily unavailable' })
    expect(pending).toHaveLength(0)
  })

  it('reuses a built snapshot for a few seconds, so cache-busting URLs cannot wake the database repeatedly', async () => {
    const db = vi.fn(() => ctx.db)
    const d = deps({ db, memoMs: 5_000 })
    await handleSnapshot(req('?mode=live&range=1h&bust=1'), d)
    await handleSnapshot(req('?mode=live&range=1h&bust=2'), d)
    await handleSnapshot(req('?mode=live&range=1h&bust=3'), d)
    expect(db).toHaveBeenCalledTimes(1)
    await handleSnapshot(req('?mode=live&range=6h'), d) // another range is another snapshot
    expect(db).toHaveBeenCalledTimes(2)
  })

  it('lets the CDN cache responses for 10 seconds and omits nulls', async () => {
    const res = await handleSnapshot(req('?mode=live'), deps({ memoMs: 0 }))
    expect(res.headers.get('cache-control')).toBe('public, max-age=0, s-maxage=10')
    expect(JSON.stringify(await res.json())).not.toContain('null')
  })
})
