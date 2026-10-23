import {
  forceCollide,
  forceLink,
  forceManyBody,
  forceSimulation,
  forceX,
  forceY,
  type Simulation,
  type SimulationLinkDatum,
  type SimulationNodeDatum,
} from 'd3-force'
import type { GEdge, GNode } from './graph'

type Positions = Map<string, { x: number; y: number }>

interface SimNode extends SimulationNodeDatum {
  id: string
  radius: number
  charge: number
}

interface SimLink extends SimulationLinkDatum<SimNode> {
  distance: number
  strength: number
}

const LINK: Record<GEdge['kind'], { distance: number; strength: number }> = {
  'dev-repo': { distance: 110, strength: 0.5 },
  'event-repo': { distance: 48, strength: 0.7 },
  'event-dev': { distance: 70, strength: 0.15 },
}

const CHARGE = { repo: -300, dev: -140, event: -45 }

/**
 * Continuous force layout that survives incremental updates: existing nodes keep their position, new
 * nodes are seeded next to a neighbour, and the simulation is only re-heated, never rebuilt.
 */
export class ForceLayout {
  private readonly nodes = new Map<string, SimNode>()
  private readonly sim: Simulation<SimNode, SimLink>
  private links: SimLink[] = []

  constructor(
    private readonly positions: Positions,
    onTick: () => void,
  ) {
    this.sim = forceSimulation<SimNode, SimLink>([])
      .alphaDecay(0.035)
      .velocityDecay(0.45)
      .force('charge', forceManyBody<SimNode>().strength((n) => n.charge).distanceMax(420))
      .force('x', forceX<SimNode>(0).strength(0.05))
      .force('y', forceY<SimNode>(0).strength(0.05))
      .force('collide', forceCollide<SimNode>((n) => n.radius + (n.charge < -200 ? 22 : 8)).strength(0.8))
      .on('tick', () => {
        for (const n of this.nodes.values()) this.positions.set(n.id, { x: n.x ?? 0, y: n.y ?? 0 })
        onTick()
      })
  }

  /** Reconciles the simulation with the visible graph and returns the ids that were added. */
  sync(nodes: GNode[], edges: GEdge[]): string[] {
    const keep = new Set(nodes.map((n) => n.id))
    for (const id of [...this.nodes.keys()]) if (!keep.has(id)) this.nodes.delete(id)

    const neighbour = new Map<string, string>()
    for (const e of edges) {
      if (!neighbour.has(e.source)) neighbour.set(e.source, e.target)
      if (!neighbour.has(e.target)) neighbour.set(e.target, e.source)
    }

    const added: string[] = []
    for (const g of nodes) {
      const radius = g.size / 2
      const existing = this.nodes.get(g.id)
      if (existing) {
        existing.radius = radius
        continue
      }
      const at = this.positions.get(g.id) ?? this.seed(neighbour.get(g.id), radius)
      this.nodes.set(g.id, { id: g.id, radius, charge: CHARGE[g.type], x: at.x, y: at.y })
      this.positions.set(g.id, at)
      added.push(g.id)
    }

    this.links = edges
      .filter((e) => this.nodes.has(e.source) && this.nodes.has(e.target))
      .map((e) => ({ source: e.source, target: e.target, ...LINK[e.kind] }))
    this.sim.nodes([...this.nodes.values()])
    this.sim.force(
      'link',
      forceLink<SimNode, SimLink>(this.links)
        .id((n) => n.id)
        .distance((l) => l.distance)
        .strength((l) => l.strength),
    )
    if (added.length > 0 || this.sim.alpha() < 0.05) this.sim.alpha(Math.max(this.sim.alpha(), 0.45)).restart()
    return added
  }

  /** Holds a node under the pointer while it is dragged. */
  pin(id: string, x: number, y: number) {
    const n = this.nodes.get(id)
    if (!n) return
    n.fx = x
    n.fy = y
    this.sim.alphaTarget(0.25).restart()
  }

  release(id: string) {
    const n = this.nodes.get(id)
    if (n) {
      n.fx = null
      n.fy = null
    }
    this.sim.alphaTarget(0)
  }

  stop() {
    this.sim.stop()
  }

  private seed(neighbourId: string | undefined, radius: number) {
    const anchor = neighbourId ? this.positions.get(neighbourId) : undefined
    const angle = Math.random() * Math.PI * 2
    if (anchor) {
      const d = 40 + radius + Math.random() * 30
      return { x: anchor.x + Math.cos(angle) * d, y: anchor.y + Math.sin(angle) * d }
    }
    const d = 120 + Math.random() * 260
    return { x: Math.cos(angle) * d, y: Math.sin(angle) * d }
  }
}
