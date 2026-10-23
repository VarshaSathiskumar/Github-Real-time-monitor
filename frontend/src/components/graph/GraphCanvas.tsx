import cytoscape, { type Core, type ElementDefinition, type StylesheetJson } from 'cytoscape'
import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react'
import type { GEdge, GNode, GraphStore } from '@/lib/graph'
import { ForceLayout } from '@/lib/graphLayout'
import { KIND_META } from '@/lib/format'
import type { EventKind } from '@/lib/types'
import { GRAPH_COLORS } from './colors'

export interface GraphCanvasHandle {
  zoomBy: (factor: number) => void
  fit: () => void
}

interface Props {
  store: GraphStore
  graph: { nodes: GNode[]; edges: GEdge[] }
  selectedId: string | null
  onSelect: (id: string | null) => void
}

const kindColor = (k: EventKind | undefined) => (k ? KIND_META[k].color : GRAPH_COLORS.event)

const style: StylesheetJson = [
  {
    selector: 'node',
    style: {
      width: 'data(size)',
      height: 'data(size)',
      'background-color': 'data(color)',
      'border-width': 1.5,
      'border-color': 'data(border)',
      'underlay-color': 'data(color)',
      'underlay-opacity': 0.14,
      'underlay-padding': 6,
      'underlay-shape': 'ellipse',
      label: 'data(label)',
      color: '#e2e8f0',
      'font-family': 'Inter, ui-sans-serif, system-ui, sans-serif',
      'font-size': 11,
      'text-valign': 'bottom',
      'text-margin-y': 5,
      'text-outline-color': '#070b16',
      'text-outline-width': 3,
      'min-zoomed-font-size': 8,
      'text-max-width': '120px',
      'text-wrap': 'ellipsis',
      'transition-property': 'opacity, border-width, underlay-opacity',
      'transition-duration': 180,
    },
  },
  { selector: 'node[type="repo"]', style: { shape: 'round-rectangle', 'font-weight': 600, 'font-size': 12 } },
  { selector: 'node[type="dev"]', style: { shape: 'ellipse', 'font-size': 10, color: '#cbd5e1' } },
  { selector: 'node[type="event"]', style: { shape: 'diamond', 'border-width': 2, 'underlay-opacity': 0.1 } },
  {
    selector: 'edge',
    style: {
      width: 'mapData(weight, 1, 8, 1, 3.5)',
      'line-color': 'data(color)',
      'curve-style': 'straight',
      opacity: 0.55,
      'transition-property': 'opacity',
      'transition-duration': 180,
    },
  },
  { selector: 'edge[kind="dev-repo"]', style: { 'line-color': '#64748b', opacity: 0.4 } },
  { selector: 'edge[kind="event-dev"]', style: { width: 1, 'line-style': 'dashed', opacity: 0.3 } },
  { selector: 'edge[kind="event-repo"]', style: { width: 1.5 } },
  { selector: '.neighbor', style: { 'underlay-opacity': 0.3 } },
  { selector: 'edge.neighbor', style: { opacity: 0.9 } },
  { selector: 'node.chosen', style: { 'border-width': 3, 'border-color': '#ffffff', 'underlay-opacity': 0.4, 'underlay-padding': 9 } },
  { selector: 'node.faded', style: { opacity: 0.16, 'text-opacity': 0.3 } },
  { selector: 'edge.faded', style: { opacity: 0.05 } },
]

function nodeElement(n: GNode, at: { x: number; y: number } | undefined): ElementDefinition {
  const color = GRAPH_COLORS[n.type]
  return {
    group: 'nodes',
    data: {
      id: n.id,
      type: n.type,
      label: n.label,
      size: n.size,
      color,
      border: n.type === 'event' ? kindColor(n.kind) : GRAPH_COLORS.border[n.type],
    },
    position: at ? { x: at.x, y: at.y } : undefined,
  }
}

function edgeElement(e: GEdge): ElementDefinition {
  return {
    group: 'edges',
    data: { id: e.id, source: e.source, target: e.target, kind: e.kind, weight: e.weight, color: '#94a3b8' },
  }
}

/**
 * Cytoscape renderer. It never rebuilds: each update diffs the visible graph against the elements on
 * screen, adds or removes only what changed, and lets the force layout move things gently.
 */
export const GraphCanvas = forwardRef<GraphCanvasHandle, Props>(function GraphCanvas(
  { store, graph, selectedId, onSelect },
  handle,
) {
  const host = useRef<HTMLDivElement>(null)
  const cyRef = useRef<Core | null>(null)
  const layoutRef = useRef<ForceLayout | null>(null)
  const fitted = useRef(false)
  const touched = useRef(false)
  const onSelectRef = useRef(onSelect)
  onSelectRef.current = onSelect

  useImperativeHandle(handle, () => ({
    zoomBy: (factor) => {
      const cy = cyRef.current
      if (!cy) return
      cy.animate(
        { zoom: { level: cy.zoom() * factor, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } } },
        { duration: 180 },
      )
    },
    fit: () => {
      const cy = cyRef.current
      if (cy && cy.nodes().length > 0) cy.animate({ fit: { eles: cy.elements(), padding: 48 } }, { duration: 350 })
    },
  }))

  // One Cytoscape instance and one simulation for the lifetime of the canvas.
  useEffect(() => {
    if (!host.current) return
    const cy = cytoscape({
      container: host.current,
      style,
      minZoom: 0.2,
      maxZoom: 3,
      wheelSensitivity: 0.25,
      boxSelectionEnabled: false,
      selectionType: 'single',
      pixelRatio: Math.min(2, window.devicePixelRatio || 1),
    })
    cyRef.current = cy

    const layout = new ForceLayout(store.positions, () => {
      cy.batch(() => {
        cy.nodes().forEach((n) => {
          if (n.grabbed()) return
          const p = store.positions.get(n.id())
          if (p) n.position(p)
        })
      })
    })
    layoutRef.current = layout

    cy.on('tap', 'node', (e) => onSelectRef.current(e.target.id()))
    cy.on('tap', (e) => {
      if (e.target === cy) onSelectRef.current(null)
    })
    cy.on('grab', 'node', (e) => {
      const p = e.target.position()
      layout.pin(e.target.id(), p.x, p.y)
    })
    cy.on('drag', 'node', (e) => {
      const p = e.target.position()
      layout.pin(e.target.id(), p.x, p.y)
    })
    cy.on('free', 'node', (e) => layout.release(e.target.id()))

    // Once the user drives the camera, automatic fitting stops.
    const touch = () => void (touched.current = true)
    host.current.addEventListener('wheel', touch, { passive: true })
    host.current.addEventListener('pointerdown', touch)

    const resize = new ResizeObserver(() => cy.resize())
    resize.observe(host.current)

    return () => {
      resize.disconnect()
      layout.stop()
      cy.destroy()
      cyRef.current = null
      layoutRef.current = null
      fitted.current = false
      touched.current = false
    }
    // The store is stable for the lifetime of the dashboard.
  }, [store])

  // Reconcile the visible graph with the canvas.
  useEffect(() => {
    const cy = cyRef.current
    const layout = layoutRef.current
    if (!cy || !layout) return

    layout.sync(graph.nodes, graph.edges)

    const wanted = new Set<string>()
    cy.batch(() => {
      for (const n of graph.nodes) {
        wanted.add(n.id)
        const existing = cy.getElementById(n.id)
        if (existing.nonempty()) {
          if (existing.hasClass('leaving')) {
            existing.stop(true).removeClass('leaving').removeStyle('opacity')
          }
          existing.data({ label: n.label, size: n.size })
          continue
        }
        const el = cy.add(nodeElement(n, store.positions.get(n.id)))
        // Grow in from a small, transparent dot so arrivals are visible without being jarring.
        el.style({ opacity: 0, width: n.size * 0.3, height: n.size * 0.3 })
        el.animate(
          { style: { opacity: 1, width: n.size, height: n.size } },
          {
            duration: 480,
            easing: 'ease-out-cubic',
            complete: () => {
              el.removeStyle('opacity width height')
            },
          },
        )
      }

      const edgeIds = new Set<string>()
      for (const e of graph.edges) {
        edgeIds.add(e.id)
        const existing = cy.getElementById(e.id)
        if (existing.nonempty()) existing.data('weight', e.weight)
        else if (cy.getElementById(e.source).nonempty() && cy.getElementById(e.target).nonempty()) {
          cy.add(edgeElement(e))
        }
      }
      cy.edges().forEach((e) => {
        if (!edgeIds.has(e.id())) e.remove()
      })
    })

    cy.nodes().forEach((n) => {
      if (wanted.has(n.id()) || n.hasClass('leaving')) return
      n.addClass('leaving')
      n.animate({ style: { opacity: 0 } }, { duration: 220, complete: () => void (n.hasClass('leaving') && n.remove()) })
    })

    if (!fitted.current && graph.nodes.length > 0) {
      fitted.current = true
      // Fit once the layout has mostly settled, then again after it has fully relaxed.
      for (const delay of [900, 2800]) {
        window.setTimeout(() => {
          if (cyRef.current !== cy || touched.current || cy.nodes().length === 0) return
          cy.animate({ fit: { eles: cy.elements(), padding: 56 } }, { duration: 500 })
        }, delay)
      }
    }
  }, [graph, store])

  // Highlight the selection and its neighbourhood, fade everything else.
  useEffect(() => {
    const cy = cyRef.current
    if (!cy) return
    cy.batch(() => {
      cy.elements().removeClass('faded neighbor chosen')
      const chosen = selectedId ? cy.getElementById(selectedId) : null
      if (!chosen || chosen.empty()) return
      const hood = chosen.closedNeighborhood()
      cy.elements().not(hood).addClass('faded')
      hood.addClass('neighbor')
      chosen.addClass('chosen')
    })
  }, [selectedId, graph])

  // Bring a newly selected node into view without changing the zoom more than needed.
  useEffect(() => {
    const cy = cyRef.current
    if (!cy || !selectedId) return
    const node = cy.getElementById(selectedId)
    if (node.empty()) return
    const zoom = Math.min(Math.max(cy.zoom(), 0.9), 1.6)
    const p = node.position()
    // Below the lg breakpoint the details sheet covers the lower part of the canvas, so aim higher.
    const targetY = window.innerWidth < 1024 ? cy.height() * 0.2 : cy.height() / 2
    cy.animate(
      { zoom, pan: { x: cy.width() / 2 - p.x * zoom, y: targetY - p.y * zoom } },
      { duration: 400, easing: 'ease-out-cubic' },
    )
    // Only on selection change, not on every graph update.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId])

  return (
    <div
      ref={host}
      role="application"
      aria-label="Interactive network graph of GitHub repositories, developers and events"
      className="absolute inset-0 touch-none"
    />
  )
})
