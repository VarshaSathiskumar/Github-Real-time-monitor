import type { FeedEvent, RisingRepo } from '../../src/lib/types.js'
import { type MinuteBucket, MemoryStore } from './memory.js'
import { evaluate, spikeToWire } from './momentum.js'
import { type Kind, type MomentumConfig, MINUTE, ZERO, iso } from './model.js'
import type { DashboardSource } from './views.js'

// Simulated GitHub activity, the stateless counterpart of DemoSimulator. Every minute of events is a pure
// function of the minute number, so any function instance renders the same picture without a database,
// without GitHub and without shared state.

const REPOS = [
  'facebook/react', 'microsoft/vscode', 'vercel/next.js', 'spring-projects/spring-boot', 'torvalds/linux',
  'tensorflow/tensorflow', 'kubernetes/kubernetes', 'golang/go', 'rust-lang/rust', 'python/cpython',
  'apache/kafka', 'apache/spark', 'redis/redis', 'hashicorp/terraform', 'grafana/grafana', 'flutter/flutter',
  'vuejs/core', 'angular/angular', 'nodejs/node', 'denoland/deno', 'django/django', 'pallets/flask',
  'dotnet/runtime', 'elastic/elasticsearch', 'prometheus/prometheus', 'home-assistant/core', 'ollama/ollama',
  'langchain-ai/langchain', 'huggingface/transformers', 'pytorch/pytorch', 'sveltejs/svelte',
  'tailwindlabs/tailwindcss', 'shadcn-ui/ui', 'supabase/supabase', 'ansible/ansible', 'godotengine/godot',
  'neovim/neovim', 'rails/rails', 'laravel/framework', 'JetBrains/kotlin',
]
const KIND_WEIGHTS: [Kind, number][] = [
  ['PUSH', 0.46], ['WATCH', 0.2], ['PULL_REQUEST', 0.1], ['ISSUE_COMMENT', 0.08], ['ISSUES', 0.05],
  ['FORK', 0.04], ['OTHER', 0.07],
]
const ADJECTIVES = ['swift', 'quiet', 'brave', 'lucky', 'clever', 'rapid', 'sunny', 'mellow', 'bold', 'keen']
const NOUNS = ['fox', 'coder', 'hacker', 'otter', 'gopher', 'ferris', 'panda', 'falcon', 'maker', 'wizard']

const EVENTS_PER_SECOND = 4
const BACKFILL_MINUTES = 360
const SPIKE_EVERY_MS = 4 * MINUTE
const SPIKE_LASTS_MS = 100_000
const SPIKE_EVENTS_PER_SECOND = 1.6

const REPO_WEIGHTS = REPOS.map((_, i) => 1 / Math.pow(i + 1, 0.9))
const WEIGHT_SUM = REPO_WEIGHTS.reduce((a, b) => a + b, 0)

function mulberry32(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const gauss = (rng: () => number): number =>
  Math.sqrt(-2 * Math.log(1 - rng())) * Math.cos(2 * Math.PI * rng())

const loadFactor = (m: number): number => 0.85 + 0.25 * Math.sin(m / 37) + 0.1 * Math.sin(m / 9)

interface DemoEvent {
  id: string
  t: number
  kind: Kind
  repo: string
  commits: number
  actor: string
}

const spikeRepoOf = (slot: number): string =>
  REPOS[REPOS.length - 1 - Math.floor(mulberry32(slot * 7919 + 13)() * Math.floor(REPOS.length / 3))]

function pickKind(rng: () => number): Kind {
  const r = rng()
  let acc = 0
  for (const [k, w] of KIND_WEIGHTS) {
    acc += w
    if (r < acc) return k
  }
  return 'PUSH'
}

function pickRepo(rng: () => number): string {
  let r = rng() * WEIGHT_SUM
  for (let i = 0; i < REPO_WEIGHTS.length; i++) {
    r -= REPO_WEIGHTS[i]
    if (r <= 0) return REPOS[i]
  }
  return REPOS[0]
}

function eventsOfMinute(m: number): DemoEvent[] {
  const rng = mulberry32(m * 2654435761)
  const start = m * MINUTE
  const raw: Omit<DemoEvent, 'id'>[] = []
  const emit = (t: number, repo: string): void => {
    const kind = pickKind(rng)
    raw.push({
      t,
      kind,
      repo,
      commits: kind === 'PUSH' ? 1 + Math.floor(rng() * 5) : 0,
      actor: `${ADJECTIVES[Math.floor(rng() * 10)]}-${NOUNS[Math.floor(rng() * 10)]}${Math.floor(rng() * 100)}`,
    })
  }

  const lambda = EVENTS_PER_SECOND * 60 * loadFactor(m)
  const n = Math.max(0, Math.round(lambda + Math.sqrt(lambda) * gauss(rng)))
  for (let i = 0; i < n; i++) emit(start + Math.floor(rng() * MINUTE), pickRepo(rng))

  // A burst on one of the quieter repositories at the start of every slot.
  for (let slot = Math.floor(start / SPIKE_EVERY_MS); slot * SPIKE_EVERY_MS < start + MINUTE; slot++) {
    const from = Math.max(start, slot * SPIKE_EVERY_MS)
    const to = Math.min(start + MINUTE, slot * SPIKE_EVERY_MS + SPIKE_LASTS_MS)
    if (to <= from) continue
    const lam = (SPIKE_EVENTS_PER_SECOND * (to - from)) / 1000
    const extra = Math.max(0, Math.round(lam + Math.sqrt(lam) * gauss(rng)))
    const repo = spikeRepoOf(slot)
    for (let i = 0; i < extra; i++) emit(from + Math.floor(rng() * (to - from)), repo)
  }

  raw.sort((a, b) => a.t - b.t)
  return raw.map((e, i) => ({ ...e, id: `demo-${m}-${i}` }))
}

// Complete minutes never change, so they are built once per function instance.
const completed = new Map<number, MinuteBucket>()

function bucketOf(events: DemoEvent[]): MinuteBucket {
  const tmp = new MemoryStore()
  for (const e of events) tmp.add(e.t, e.kind, e.repo, e.commits)
  return tmp.minutes.values().next().value ?? { counts: ZERO, repos: new Map() }
}

function describe(e: DemoEvent): string {
  switch (e.kind) {
    case 'PUSH':
      return `${e.actor} pushed ${e.commits} ${e.commits === 1 ? 'commit' : 'commits'} to ${e.repo}`
    case 'PULL_REQUEST':
      return `${e.actor} opened a pull request in ${e.repo}`
    case 'FORK':
      return `${e.actor} forked ${e.repo}`
    case 'WATCH':
      return `${e.actor} starred ${e.repo}`
    case 'ISSUES':
      return `${e.actor} opened an issue in ${e.repo}`
    case 'ISSUE_COMMENT':
      return `${e.actor} commented on an issue in ${e.repo}`
    default:
      return `${e.actor} triggered create activity in ${e.repo}`
  }
}

/** The demo universe at the given instant: six hours of history ending now. */
export function demoSource(now: number, momentum: MomentumConfig): DashboardSource {
  const nowMinute = Math.floor(now / MINUTE)
  const store = new MemoryStore()
  for (let m = nowMinute - BACKFILL_MINUTES; m <= nowMinute; m++) {
    if (m < nowMinute) {
      let b = completed.get(m)
      if (!b) completed.set(m, (b = bucketOf(eventsOfMinute(m))))
      store.minutes.set(m, b)
    } else {
      // The minute in progress only contains what has "happened" so far.
      store.minutes.set(m, bucketOf(eventsOfMinute(m).filter((e) => e.t <= now)))
    }
  }
  for (const m of completed.keys()) if (m < nowMinute - BACKFILL_MINUTES - 1) completed.delete(m)

  // Demo starts with six hours of history, so one baseline window is enough to compare against.
  const cfg: MomentumConfig = { ...momentum, minHistoryWindows: 1, minEvents: Math.min(momentum.minEvents, 8) }

  return {
    mode: 'demo',
    now: () => now,
    store,
    observationStart: async () => {
      const first = store.firstMinute()
      return first === null ? null : first * MINUTE
    },
    recentEvents: async (limit): Promise<FeedEvent[]> => {
      const events: DemoEvent[] = []
      for (let m = nowMinute; m >= nowMinute - 2; m--) events.push(...eventsOfMinute(m).filter((e) => e.t <= now))
      return events
        .sort((a, b) => b.t - a.t)
        .slice(0, limit)
        .map((e) => ({
          id: e.id,
          kind: e.kind,
          type: e.kind,
          actor: e.actor,
          repo: e.repo,
          repoUrl: `https://github.com/${e.repo}`,
          description: describe(e),
          url: `https://github.com/${e.repo}`,
          createdAt: iso(e.t),
          observedAt: iso(e.t),
          demo: true,
        }))
    },
    rising: async (): Promise<RisingRepo[]> => {
      const size = cfg.windowMinutes
      const currentWindow = Math.floor(nowMinute / size) * size
      const firstWindow = Math.floor((store.firstMinute() ?? nowMinute) / size) * size
      const perMinute = (repo: string, a: number, z: number): number => {
        let n = 0
        for (let m = a; m <= z; m++) n += store.minutes.get(m)?.repos.get(repo)?.total ?? 0
        return n
      }
      const current = new Map<string, number>()
      for (let m = currentWindow; m <= nowMinute; m++) {
        for (const [repo, c] of store.minutes.get(m)?.repos ?? []) current.set(repo, (current.get(repo) ?? 0) + c.total)
      }
      const out: RisingRepo[] = []
      for (const [repo, count] of current) {
        if (count < cfg.minEvents) continue
        const history = new Map<number, number>()
        for (let i = 1; i <= cfg.baselineWindows; i++) {
          const ws = currentWindow - i * size
          history.set(ws * MINUTE, perMinute(repo, ws, ws + size - 1))
        }
        const spike = evaluate(cfg, repo, currentWindow * MINUTE, count, history, firstWindow * MINUTE, now)
        if (spike) out.push(spikeToWire(spike))
      }
      return out.sort((a, b) => b.currentCount - a.currentCount).slice(0, 10)
    },
  }
}
