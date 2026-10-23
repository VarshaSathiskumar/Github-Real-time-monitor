import { AnimatePresence, motion } from 'framer-motion'
import { Maximize2, Network, Pause, Play, ZoomIn, ZoomOut } from 'lucide-react'
import { useRef } from 'react'
import { formatCount } from '@/lib/format'
import type { useGraph } from '@/hooks/useGraph'
import type { Mode } from '@/lib/types'
import { cn } from '@/lib/utils'
import { EmptyState } from '../EmptyState'
import { SectionHeader } from '../SectionHeader'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { GraphCanvas, type GraphCanvasHandle } from './GraphCanvas'
import { GraphPanel, NodeSwatch } from './GraphPanel'

interface Props {
  graph: ReturnType<typeof useGraph>
  mode: Mode
  /** false until the first event snapshot has been requested */
  loaded: boolean
}

function IconButton({ label, onClick, children }: { label: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={onClick}
      className="grid h-9 w-9 place-items-center text-slate-300 transition-colors hover:bg-white/10 hover:text-white"
    >
      {children}
    </button>
  )
}

export function GraphView({ graph: g, mode, loaded }: Props) {
  const canvas = useRef<GraphCanvasHandle>(null)
  const { store, selectedId } = g
  const empty = store.isEmpty

  return (
    <Card className="overflow-hidden">
      <SectionHeader
        title="GitHub Activity Network"
        mode={mode}
        description={
          <span className="hidden sm:inline">
            Repositories, developers and events from the sampled public events stream, connected as they happen.
            This is the observed sample, not all of GitHub.
          </span>
        }
      />

      <div className="relative flex h-[calc(100svh-260px)] min-h-[520px] max-h-[900px] border-t border-white/[0.07]">
        <div
          className="relative min-w-0 flex-1"
          style={{
            backgroundImage: 'radial-gradient(rgb(255 255 255 / 0.07) 1px, transparent 1px)',
            backgroundSize: '26px 26px',
          }}
        >
          <GraphCanvas ref={canvas} store={store} graph={g.graph} selectedId={selectedId} onSelect={g.select} />

          {/* Legend with live counts */}
          <div className="pointer-events-none absolute left-3 top-3 flex max-w-[calc(100%-7rem)] flex-wrap gap-1.5">
            {(['repo', 'dev', 'event'] as const).map((t) => (
              <span
                key={t}
                className="inline-flex items-center gap-1.5 rounded-full border border-white/10 bg-background/70 px-2.5 py-1 text-[11px] text-slate-300 backdrop-blur-md"
              >
                <NodeSwatch type={t} />
                <span className="hidden sm:inline">{t === 'repo' ? 'Repositories' : t === 'dev' ? 'Developers' : 'Events'}</span>
                <span className="num font-medium text-white">
                  {formatCount(t === 'repo' ? store.repos.size : t === 'dev' ? store.devs.size : g.graph.nodes.filter((n) => n.type === 'event').length)}
                </span>
              </span>
            ))}
          </div>

          {/* View controls */}
          <div className="absolute right-3 top-3 flex flex-col items-end gap-2">
            <div className="flex overflow-hidden rounded-xl border border-white/10 bg-background/70 backdrop-blur-md">
              <IconButton label="Zoom in" onClick={() => canvas.current?.zoomBy(1.4)}>
                <ZoomIn className="h-4 w-4" aria-hidden />
              </IconButton>
              <IconButton label="Zoom out" onClick={() => canvas.current?.zoomBy(1 / 1.4)}>
                <ZoomOut className="h-4 w-4" aria-hidden />
              </IconButton>
              <IconButton label="Fit graph to view" onClick={() => canvas.current?.fit()}>
                <Maximize2 className="h-4 w-4" aria-hidden />
              </IconButton>
            </div>
            <Button
              variant={g.paused ? 'primary' : 'outline'}
              size="sm"
              className="bg-background/70 backdrop-blur-md"
              onClick={() => g.setPaused(!g.paused)}
              aria-pressed={g.paused}
            >
              {g.paused ? <Play className="h-3.5 w-3.5" aria-hidden /> : <Pause className="h-3.5 w-3.5" aria-hidden />}
              {g.paused ? (g.pendingCount > 0 ? `Resume (${g.pendingCount})` : 'Resume') : 'Pause'}
            </Button>
          </div>

          {empty && (
            <div className="pointer-events-none absolute inset-0 grid place-items-center">
              <EmptyState icon={Network} title={loaded ? 'Waiting for GitHub events' : 'Loading the network'}>
                {loaded
                  ? 'Repositories, developers and events appear here as soon as they are processed.'
                  : 'Fetching the most recent events.'}
              </EmptyState>
            </div>
          )}

          <p className="pointer-events-none absolute bottom-3 left-3 hidden text-[11px] text-slate-500 sm:block">
            Scroll to zoom, drag to pan, click a node for details
          </p>
        </div>

        {/* Desktop: permanent side panel */}
        <div className="hidden w-[340px] shrink-0 border-l border-white/[0.07] bg-background/40 lg:block">
          <GraphPanel
            store={store}
            mode={mode}
            selectedId={selectedId}
            expanded={g.expanded}
            onSelect={g.select}
            onCollapse={g.collapse}
            onClose={() => g.select(null)}
          />
        </div>

        {/* Mobile and tablet: bottom sheet while something is selected */}
        <AnimatePresence>
          {selectedId && (
            <motion.div
              key="sheet"
              initial={{ y: '100%' }}
              animate={{ y: 0 }}
              exit={{ y: '100%' }}
              transition={{ type: 'spring', stiffness: 380, damping: 36 }}
              className={cn(
                'absolute inset-x-0 bottom-0 z-10 max-h-[62%] min-h-[220px] overflow-hidden rounded-t-2xl lg:hidden',
                'border-t border-white/10 bg-popover/95 shadow-[0_-20px_40px_-20px_rgb(0_0_0/0.8)] backdrop-blur-xl',
              )}
            >
              <GraphPanel
                store={store}
                mode={mode}
                selectedId={selectedId}
                expanded={g.expanded}
                onSelect={g.select}
                onCollapse={g.collapse}
                onClose={() => g.select(null)}
              />
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </Card>
  )
}
