import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { GraphStore, visibleGraph } from '@/lib/graph'
import type { FeedEvent, Mode } from '@/lib/types'
import type { FeedListener } from './useDashboard'

const PENDING_LIMIT = 300

/**
 * Keeps a bounded {@link GraphStore} in sync with the dashboard's event stream. Events are folded in
 * incrementally; only the version counter changes, so React re-renders without copying the graph.
 */
export function useGraph(mode: Mode, subscribeEvents: (l: FeedListener) => () => void) {
  const store = useMemo(() => new GraphStore(), [])
  const [version, setVersion] = useState(0)
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set())
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [paused, setPausedState] = useState(false)
  const [pendingCount, setPendingCount] = useState(0)

  const pausedRef = useRef(false)
  const pending = useRef<FeedEvent[]>([])

  const fold = useCallback(
    (events: FeedEvent[], eventsMode: Mode) => {
      if (store.mode !== eventsMode) {
        // Live and demo data never share a graph.
        store.clear(eventsMode)
        setExpanded(new Set())
        setSelectedId(null)
      }
      // The stream is newest first; the store wants arrival order.
      if (store.ingest([...events].reverse()) > 0) setVersion((v) => v + 1)
    },
    [store],
  )

  useEffect(
    () =>
      subscribeEvents((events, eventsMode) => {
        if (pausedRef.current) {
          pending.current = [...events, ...pending.current].slice(0, PENDING_LIMIT)
          setPendingCount(pending.current.length)
          return
        }
        fold(events, eventsMode)
      }),
    [subscribeEvents, fold],
  )

  // Switching between live and demo drops the previous universe immediately.
  useEffect(() => {
    pending.current = []
    setPendingCount(0)
    if (store.mode !== mode) {
      store.clear(mode)
      setExpanded(new Set())
      setSelectedId(null)
      setVersion((v) => v + 1)
    }
  }, [mode, store])

  const setPaused = useCallback(
    (value: boolean) => {
      pausedRef.current = value
      setPausedState(value)
      if (!value && pending.current.length > 0) {
        const queued = pending.current
        pending.current = []
        setPendingCount(0)
        fold(queued, store.mode)
      }
    },
    [fold, store],
  )

  /** Selecting a repository also expands it; selecting anything else keeps expansions as they are. */
  const select = useCallback((id: string | null) => {
    setSelectedId(id)
    if (id?.startsWith('r:')) setExpanded((prev) => (prev.has(id) ? prev : new Set(prev).add(id)))
  }, [])

  const collapse = useCallback((id: string) => {
    setExpanded((prev) => {
      if (!prev.has(id)) return prev
      const next = new Set(prev)
      next.delete(id)
      return next
    })
  }, [])

  // A selection that was evicted from the store no longer points at anything.
  useEffect(() => {
    if (selectedId && !store.get(selectedId)) setSelectedId(null)
  }, [version, selectedId, store])

  // Expanded repositories that were evicted are forgotten so the set cannot grow without bound.
  useEffect(() => {
    setExpanded((prev) => {
      const next = [...prev].filter((id) => store.repos.has(id))
      return next.length === prev.size ? prev : new Set(next)
    })
  }, [version, store])

  const graph = useMemo(
    () => visibleGraph(store, { expanded, selectedId }),
    // `version` stands in for store mutations.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [store, version, expanded, selectedId],
  )

  return { store, graph, version, expanded, selectedId, select, collapse, paused, pendingCount, setPaused }
}
