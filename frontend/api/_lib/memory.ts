import { type Counts, type Kind, ZERO, countsOf, plus } from './model.js'
import type { ActivityStore, MinuteRow } from './views.js'

export interface MinuteBucket {
  counts: Counts
  repos: Map<string, Counts>
}

/** In-memory ActivityStore, the port of DemoDataStore: simulated activity never touches the database. */
export class MemoryStore implements ActivityStore {
  readonly minutes = new Map<number, MinuteBucket>()

  add(at: number, kind: Kind, repo: string, commits: number | null): void {
    const minute = Math.floor(at / 60_000)
    let b = this.minutes.get(minute)
    if (!b) this.minutes.set(minute, (b = { counts: ZERO, repos: new Map() }))
    const one = countsOf(kind, commits)
    b.counts = plus(b.counts, one)
    b.repos.set(repo, plus(b.repos.get(repo) ?? ZERO, one))
  }

  firstMinute(): number | null {
    let first: number | null = null
    for (const m of this.minutes.keys()) if (first === null || m < first) first = m
    return first
  }

  private between(from: number, to: number): [number, MinuteBucket][] {
    const a = Math.floor(from / 60_000)
    const z = Math.floor((to - 1000) / 1000 / 60)
    return [...this.minutes.entries()].filter(([m]) => m >= a && m <= z).sort(([x], [y]) => x - y)
  }

  async minuteCounts(from: number, to: number): Promise<MinuteRow[]> {
    return this.between(from, to).map(([m, b]) => ({ t: m * 60_000, c: b.counts }))
  }

  async activeRepositories(from: number, to: number): Promise<number> {
    const repos = new Set<string>()
    for (const [, b] of this.between(from, to)) for (const r of b.repos.keys()) repos.add(r)
    return repos.size
  }

  async activeRepositorySeries(from: number, to: number, bucketMinutes: number): Promise<number[]> {
    const fromMinute = Math.floor(from / 60_000)
    const first = Math.floor(fromMinute / bucketMinutes) * bucketMinutes
    const last = Math.floor((to - 1000) / 1000 / 60)
    const out: number[] = []
    for (let b = first; b <= last; b += bucketMinutes) {
      const repos = new Set<string>()
      for (const [m, bucket] of this.minutes) {
        // Like the SQL store: the first bucket only counts minutes inside the window.
        if (m >= Math.max(b, fromMinute) && m <= Math.min(last, b + bucketMinutes - 1)) for (const r of bucket.repos.keys()) repos.add(r)
      }
      out.push(repos.size)
    }
    return out
  }

  async repositoryTotals(from: number, to: number, limit: number): Promise<Map<string, Counts>> {
    const totals = new Map<string, Counts>()
    for (const [, b] of this.between(from, to)) {
      for (const [r, c] of b.repos) totals.set(r, plus(totals.get(r) ?? ZERO, c))
    }
    return new Map([...totals.entries()].sort(([, a], [, b]) => b.total - a.total).slice(0, limit))
  }

  async repositoryMinutes(repos: string[], from: number, to: number): Promise<Map<string, MinuteRow[]>> {
    const out = new Map<string, MinuteRow[]>(repos.map((r) => [r, []]))
    for (const [m, b] of this.between(from, to)) {
      for (const r of repos) {
        const c = b.repos.get(r)
        if (c) out.get(r)!.push({ t: m * 60_000, c })
      }
    }
    return out
  }
}
