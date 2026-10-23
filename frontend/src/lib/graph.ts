import type { EventKind, FeedEvent, Mode } from './types'

export type GraphNodeType = 'repo' | 'dev' | 'event'
export type GraphEdgeKind = 'dev-repo' | 'event-repo' | 'event-dev'

export interface GraphLimits {
  repos: number
  devs: number
  events: number
}

/** Upper bounds that keep the graph readable and the layout cheap. */
export const GRAPH_LIMITS: GraphLimits = { repos: 60, devs: 120, events: 200 }
/** Freshest events always drawn; older ones appear when their repository or developer is selected. */
export const LIVE_EVENT_NODES = 20

export interface RepoNode {
  type: 'repo'
  id: string
  name: string
  url: string
  /** events observed for this repository since it entered the graph */
  events: number
  touch: number
}

export interface DevNode {
  type: 'dev'
  id: string
  login: string
  url: string
  events: number
  touch: number
}

export interface EventNode {
  type: 'event'
  id: string
  event: FeedEvent
  repoId: string
  devId: string
}

export type StoredNode = RepoNode | DevNode | EventNode

/** Render-ready node description produced by {@link visibleGraph}. */
export interface GNode {
  id: string
  type: GraphNodeType
  label: string
  size: number
  kind?: EventKind
}

export interface GEdge {
  id: string
  source: string
  target: string
  kind: GraphEdgeKind
  weight: number
}

export const repoId = (name: string) => `r:${name.toLowerCase()}`
export const devId = (login: string) => `d:${login.toLowerCase()}`
export const eventId = (id: string) => `e:${id}`

const SEEN_LIMIT = 5000

/**
 * In-memory graph of repositories, developers and events built from the feed.
 *
 * Ingestion is incremental and idempotent: an event id is processed once, counters only grow, and the
 * least recently touched repositories, developers and oldest events are evicted once a limit is exceeded.
 */
export class GraphStore {
  mode: Mode = 'live'
  readonly repos = new Map<string, RepoNode>()
  readonly devs = new Map<string, DevNode>()
  /** Insertion ordered, so the first entry is always the oldest event. */
  readonly events = new Map<string, EventNode>()
  /** repo id -> developer id -> events by that developer in that repository */
  readonly repoDevs = new Map<string, Map<string, number>>()
  readonly devRepos = new Map<string, Map<string, number>>()
  /** Last known layout positions, so a remounted canvas continues where it stopped. */
  readonly positions = new Map<string, { x: number; y: number }>()

  private readonly seen = new Set<string>()
  private clock = 0

  constructor(private readonly limits: GraphLimits = GRAPH_LIMITS) {}

  clear(mode: Mode) {
    this.mode = mode
    this.repos.clear()
    this.devs.clear()
    this.events.clear()
    this.repoDevs.clear()
    this.devRepos.clear()
    this.positions.clear()
    this.seen.clear()
    this.clock = 0
  }

  get isEmpty() {
    return this.repos.size === 0
  }

  get(id: string): StoredNode | undefined {
    return this.repos.get(id) ?? this.devs.get(id) ?? this.events.get(id)
  }

  /** Adds events given oldest first; returns how many were new. */
  ingest(events: FeedEvent[]): number {
    let added = 0
    for (const e of events) {
      if (!e.id || !e.repo || !e.actor || this.seen.has(e.id)) continue
      this.remember(e.id)
      added++
      const touch = ++this.clock

      const rid = repoId(e.repo)
      const did = devId(e.actor)
      const repo = this.repos.get(rid) ?? this.addRepo(rid, e)
      const dev = this.devs.get(did) ?? this.addDev(did, e)
      repo.events++
      repo.touch = touch
      dev.events++
      dev.touch = touch
      bump(this.repoDevs, rid, did)
      bump(this.devRepos, did, rid)
      this.events.set(eventId(e.id), { type: 'event', id: eventId(e.id), event: e, repoId: rid, devId: did })
    }
    if (added > 0) this.evict()
    return added
  }

  /** Newest first. */
  eventsOfRepo(id: string, limit = Infinity): EventNode[] {
    return this.collect((n) => n.repoId === id, limit)
  }

  eventsOfDev(id: string, limit = Infinity): EventNode[] {
    return this.collect((n) => n.devId === id, limit)
  }

  /** Developers in a repository, most active first. */
  developersOf(repo: string): { dev: DevNode; events: number }[] {
    return ranked(this.repoDevs.get(repo), this.devs)
  }

  /** Repositories a developer was seen in, most active first. */
  repositoriesOf(dev: string): { repo: RepoNode; events: number }[] {
    return ranked(this.devRepos.get(dev), this.repos).map(({ dev: repo, events }) => ({ repo, events }))
  }

  private collect(match: (n: EventNode) => boolean, limit: number): EventNode[] {
    const out: EventNode[] = []
    for (const n of [...this.events.values()].reverse()) {
      if (!match(n)) continue
      out.push(n)
      if (out.length >= limit) break
    }
    return out
  }

  private addRepo(id: string, e: FeedEvent): RepoNode {
    const node: RepoNode = { type: 'repo', id, name: e.repo, url: e.repoUrl || `https://github.com/${e.repo}`, events: 0, touch: 0 }
    this.repos.set(id, node)
    return node
  }

  private addDev(id: string, e: FeedEvent): DevNode {
    const node: DevNode = { type: 'dev', id, login: e.actor, url: `https://github.com/${e.actor}`, events: 0, touch: 0 }
    this.devs.set(id, node)
    return node
  }

  private remember(id: string) {
    this.seen.add(id)
    if (this.seen.size > SEEN_LIMIT) {
      const oldest = this.seen.values().next().value
      if (oldest !== undefined) this.seen.delete(oldest)
    }
  }

  private evict() {
    while (this.repos.size > this.limits.repos) this.removeRepo(leastRecent(this.repos))
    while (this.devs.size > this.limits.devs) this.removeDev(leastRecent(this.devs))
    while (this.events.size > this.limits.events) {
      const oldest = this.events.keys().next().value
      if (oldest === undefined) break
      this.events.delete(oldest)
      this.positions.delete(oldest)
    }
  }

  private removeRepo(id: string | undefined) {
    if (!id) return
    this.repos.delete(id)
    this.positions.delete(id)
    for (const [eid, n] of this.events) {
      if (n.repoId !== id) continue
      this.events.delete(eid)
      this.positions.delete(eid)
    }
    for (const did of this.repoDevs.get(id)?.keys() ?? []) {
      const links = this.devRepos.get(did)
      links?.delete(id)
      // A developer only seen in evicted repositories has nothing left to connect to.
      if (!links || links.size === 0) this.removeDev(did)
    }
    this.repoDevs.delete(id)
  }

  private removeDev(id: string | undefined) {
    if (!id || !this.devs.has(id)) return
    this.devs.delete(id)
    this.positions.delete(id)
    for (const [eid, n] of this.events) {
      if (n.devId !== id) continue
      this.events.delete(eid)
      this.positions.delete(eid)
    }
    for (const rid of this.devRepos.get(id)?.keys() ?? []) this.repoDevs.get(rid)?.delete(id)
    this.devRepos.delete(id)
  }
}

function bump(map: Map<string, Map<string, number>>, a: string, b: string) {
  let inner = map.get(a)
  if (!inner) map.set(a, (inner = new Map()))
  inner.set(b, (inner.get(b) ?? 0) + 1)
}

function leastRecent(nodes: Map<string, { touch: number }>): string | undefined {
  let best: string | undefined
  let bestTouch = Infinity
  for (const [id, n] of nodes) {
    if (n.touch < bestTouch) {
      best = id
      bestTouch = n.touch
    }
  }
  return best
}

function ranked<T>(links: Map<string, number> | undefined, nodes: Map<string, T>) {
  const out: { dev: T; events: number }[] = []
  for (const [id, events] of links ?? []) {
    const dev = nodes.get(id)
    if (dev) out.push({ dev, events })
  }
  return out.sort((a, b) => b.events - a.events)
}

const logSize = (base: number, per: number, n: number, max: number) =>
  Math.min(max, base + per * Math.log2(1 + n))

export interface VisibleOptions {
  /** Repositories whose complete event history is drawn. */
  expanded: ReadonlySet<string>
  selectedId?: string | null
  liveEvents?: number
}

/**
 * Elements to draw: every repository and developer, the freshest events, and the events of expanded
 * repositories or of the selected developer. Pure, so the canvas can diff it against what it shows.
 */
export function visibleGraph(store: GraphStore, opts: VisibleOptions): { nodes: GNode[]; edges: GEdge[] } {
  const nodes: GNode[] = []
  const edges: GEdge[] = []
  const live = opts.liveEvents ?? LIVE_EVENT_NODES

  for (const r of store.repos.values()) {
    nodes.push({ id: r.id, type: 'repo', label: r.name, size: logSize(24, 5, r.events, 52) })
  }
  for (const d of store.devs.values()) {
    nodes.push({ id: d.id, type: 'dev', label: d.login, size: logSize(14, 3, d.events, 28) })
  }
  for (const [rid, devs] of store.repoDevs) {
    for (const [did, weight] of devs) {
      if (store.repos.has(rid) && store.devs.has(did)) {
        edges.push({ id: `${did}|${rid}`, source: did, target: rid, kind: 'dev-repo', weight })
      }
    }
  }

  const selectedDev = opts.selectedId?.startsWith('d:') ? opts.selectedId : null
  const freshFrom = Math.max(0, store.events.size - live)
  let index = 0
  for (const n of store.events.values()) {
    const fresh = index++ >= freshFrom
    const shown = fresh || opts.expanded.has(n.repoId) || n.devId === selectedDev || n.id === opts.selectedId
    if (!shown) continue
    nodes.push({ id: n.id, type: 'event', label: '', size: 9, kind: n.event.kind })
    edges.push({ id: `${n.id}|${n.repoId}`, source: n.id, target: n.repoId, kind: 'event-repo', weight: 1 })
    edges.push({ id: `${n.id}|${n.devId}`, source: n.id, target: n.devId, kind: 'event-dev', weight: 1 })
  }
  return { nodes, edges }
}
