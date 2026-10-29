import { describe, expect, it } from 'vitest'
import { writeResilient, type Row } from './ingest.js'
import { MemoryStore } from './memory.js'
import { evaluate } from './momentum.js'
import { type MomentumConfig, RANGES, compact, countsOf, iso, settingsFromEnv } from './model.js'
import { parseEvent } from './parser.js'
import { liveSource } from './postgres.js'
import { fixture, testDb } from './test-db.js'
import { type DashboardSource, activity, breakdown, metrics, trending } from './views.js'

// The expected files in parity/ are produced by the Java implementation (ServerlessParityTest) and asserted
// there too. These tests check that the TypeScript port produces exactly the same output.

const strip = (x: unknown): unknown => {
  const drop = (v: unknown): void => {
    if (Array.isArray(v)) v.forEach(drop)
    else if (v && typeof v === 'object') {
      delete (v as Record<string, unknown>).generatedAt
      Object.values(v).forEach(drop)
    }
  }
  // JSON.stringify drops undefined and nulls are removed like the HTTP responses do
  const copy = JSON.parse(JSON.stringify(x, (_k, v: unknown) => (v === null ? undefined : v))) as unknown
  drop(copy)
  return copy
}

describe('event parsing parity with EventParser', () => {
  const inputs = fixture('events.json') as { name: string; raw: unknown }[]
  const expected = fixture('parse.expected.json') as Record<string, unknown>[]

  it('has a golden entry for every input', () => {
    expect(expected.map((e) => e.name)).toEqual(inputs.map((i) => i.name))
  })

  for (const [i, c] of inputs.entries()) {
    it(c.name, () => {
      const want = expected[i]
      let got: Record<string, unknown>
      try {
        const e = parseEvent(c.raw)
        got = {
          name: c.name, ok: true, id: e.id, type: e.type, kind: e.kind, actor: e.actor, repoId: e.repoId,
          repoName: e.repoName, repoUrl: e.repoUrl, commits: e.commits ?? undefined, action: e.action ?? undefined,
          description: e.description, url: e.url, createdAt: iso(e.createdAt), ingestedAt: iso(e.ingestedAt),
          counts: compact(countsOf(e.kind, e.commits)),
        }
      } catch {
        got = { name: c.name, ok: false }
      }
      expect(strip(got)).toEqual(want)
    })
  }
})

describe('momentum rule parity with MomentumRules', () => {
  const inputs = (fixture('momentum.json') as { cases: Record<string, any>[] }).cases
  const expected = fixture('momentum.expected.json') as { name: string; spike?: unknown }[]

  for (const [i, c] of inputs.entries()) {
    it(c.name, () => {
      const cfg: MomentumConfig = { ...c.cfg, visibleForMinutes: 15 }
      const history = new Map<number, number>(Object.entries(c.history as Record<string, number>).map(([k, v]) => [Number(k), v]))
      const spike = evaluate(cfg, c.repo, c.windowStart, c.currentCount, history, c.firstWindowStart, c.detectedAt)
      expect(strip({ name: c.name, spike: spike ?? undefined })).toEqual(expected[i])
    })
  }
})

describe('dashboard view parity with DashboardService', () => {
  const scenarios = (fixture('views.json') as { scenarios: { name: string; now: number; events: [number, any, string, number | null][] }[] }).scenarios
  const golden = fixture('views.expected.json') as Record<string, Record<string, Record<string, unknown>>>
  const { score, momentum } = settingsFromEnv({})

  async function views(src: DashboardSource, code: string): Promise<Record<string, unknown>> {
    const range = RANGES.find((r) => r.code === code)!
    return {
      metrics: await metrics(src, range),
      activity: await activity(src, range),
      trending: await trending(src, range, score),
      breakdown: await breakdown(src, range),
    }
  }

  for (const s of scenarios) {
    it(`${s.name}: in-memory store matches the Java output`, async () => {
      const store = new MemoryStore()
      for (const [t, kind, repo, commits] of s.events) store.add(t, kind, repo, commits)
      const first = store.firstMinute()
      const src: DashboardSource = {
        mode: 'live', now: () => s.now, store, observationStart: async () => (first === null ? null : first * 60_000),
        recentEvents: async () => [], rising: async () => [],
      }
      for (const r of RANGES) expect(strip(await views(src, r.code))).toEqual(golden[s.name][r.code])
    })

    it(`${s.name}: SQL store, fed through the real write path, matches the Java output`, async () => {
      const { db, pg } = await testDb()
      const rows: Row[] = s.events.map(([t, kind, repo, commits], i) => ({
        event_id: i + 1, event_type: kind, kind, actor_login: 'a', repo_id: 1, repo_name: repo, commits,
        action: null, description: 'd', url: 'u', created_at: new Date(t).toISOString(),
        ingested_at: new Date(t).toISOString(), observed_at: new Date(t).toISOString(),
      }))
      // in slices, like several polls would
      for (let i = 0; i < rows.length; i += 250) await writeResilient(db, rows.slice(i, i + 250), [], new Map())
      const src = liveSource(db, momentum, () => s.now)
      for (const r of RANGES) expect(strip(await views(src, r.code))).toEqual(golden[s.name][r.code])
      await pg.close()
    })
  }
})
