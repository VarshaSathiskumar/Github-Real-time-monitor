import { useCallback, useEffect, useReducer, useRef, useState } from 'react'
import { api } from '@/lib/api'
import { wsUrl } from '@/lib/config'
import { mergeEvents } from '@/lib/feed'
import type {
  Activity,
  Breakdown,
  ConnectionState,
  FeedEvent,
  Metrics,
  Mode,
  RangeCode,
  Rising,
  RisingRepo,
  SystemStatus,
  Trending,
  WsEnvelope,
} from '@/lib/types'

interface State {
  mode: Mode
  range: RangeCode
  metrics?: Metrics
  activity?: Activity
  trending?: Trending
  breakdown?: Breakdown
  rising?: Rising
  status?: SystemStatus
  events: FeedEvent[]
  eventsLoaded: boolean
  loadFailed: boolean
  lastUpdated?: number
  connection: ConnectionState
  alert?: RisingRepo
}

type Action =
  | { type: 'context'; mode: Mode; range: RangeCode }
  | { type: 'data'; key: 'metrics' | 'activity' | 'trending' | 'breakdown' | 'rising' | 'status'; value: unknown }
  | { type: 'events'; events: FeedEvent[]; replace?: boolean }
  | { type: 'connection'; value: ConnectionState }
  | { type: 'alert'; value: RisingRepo | undefined }
  | { type: 'failed'; value: boolean }

const initial: State = {
  mode: 'live',
  range: '1h',
  events: [],
  eventsLoaded: false,
  loadFailed: false,
  connection: 'connecting',
}

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case 'context':
      // Switching universe clears everything; switching range only the range-dependent views.
      return action.mode !== state.mode
        ? { ...initial, mode: action.mode, range: action.range, connection: state.connection }
        : {
            ...state,
            range: action.range,
            metrics: undefined,
            activity: undefined,
            trending: undefined,
            breakdown: undefined,
          }
    case 'data':
      return { ...state, [action.key]: action.value, lastUpdated: Date.now(), loadFailed: false }
    case 'events':
      return {
        ...state,
        events: action.replace ? action.events : mergeEvents(state.events, action.events),
        eventsLoaded: true,
        lastUpdated: Date.now(),
      }
    case 'connection':
      return { ...state, connection: action.value }
    case 'alert':
      return { ...state, alert: action.value }
    case 'failed':
      return { ...state, loadFailed: action.value }
  }
}

function backoffMs(attempt: number): number {
  const base = Math.min(15_000, 1000 * 2 ** Math.min(attempt, 4))
  return base / 2 + Math.random() * (base / 2)
}

export type FeedListener = (events: FeedEvent[], mode: Mode) => void

export function useDashboard() {
  const [state, dispatch] = useReducer(reducer, initial)
  const [paused, setPausedState] = useState(false)
  const [pendingCount, setPendingCount] = useState(0)

  const ctx = useRef({ mode: state.mode, range: state.range })
  const pausedRef = useRef(false)
  const pending = useRef<FeedEvent[]>([])
  const socket = useRef<WebSocket | null>(null)
  const listeners = useRef(new Set<FeedListener>())

  ctx.current = { mode: state.mode, range: state.range }

  /** Lets other views (the network graph) tap the same event stream, newest first, unaffected by feed pause. */
  const subscribeEvents = useCallback((listener: FeedListener) => {
    listeners.current.add(listener)
    return () => void listeners.current.delete(listener)
  }, [])

  const publish = useCallback((events: FeedEvent[], mode: Mode) => {
    for (const l of listeners.current) l(events, mode)
  }, [])

  const subscribe = useCallback(() => {
    const ws = socket.current
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ action: 'subscribe', mode: ctx.current.mode, range: ctx.current.range }))
    }
  }, [])

  /** Loads a full REST snapshot; used on start, on context change and after every reconnect. */
  const loadSnapshot = useCallback(async () => {
    const { mode, range } = ctx.current
    const current = () => ctx.current.mode === mode && ctx.current.range === range
    const put =
      (key: 'metrics' | 'activity' | 'trending' | 'breakdown' | 'rising' | 'status') => (value: unknown) => {
        if (current()) dispatch({ type: 'data', key, value })
      }
    const results = await Promise.allSettled([
      api.metrics(mode, range).then(put('metrics')),
      api.activity(mode, range).then(put('activity')),
      api.trending(mode, range).then(put('trending')),
      api.breakdown(mode, range).then(put('breakdown')),
      api.rising(mode).then(put('rising')),
      api.status(mode).then(put('status')),
      api.events(mode).then((events) => {
        if (!current()) return
        dispatch({ type: 'events', events, replace: true })
        publish(events, mode)
      }),
    ])
    if (current() && results.every((r) => r.status === 'rejected')) dispatch({ type: 'failed', value: true })
  }, [publish])

  // Context (mode or range) changes: reset views, reload and tell the server what to stream.
  useEffect(() => {
    pending.current = []
    setPendingCount(0)
    void loadSnapshot()
    subscribe()
  }, [state.mode, state.range, loadSnapshot, subscribe])

  // WebSocket with exponential backoff; every reconnect pulls a fresh snapshot to recover missed updates.
  useEffect(() => {
    let closed = false
    let attempt = 0
    let timer: number | undefined

    const handle = (msg: WsEnvelope) => {
      if (msg.type === 'hello') return
      if (msg.mode !== ctx.current.mode) return
      if (msg.range && msg.range !== ctx.current.range) return
      switch (msg.type) {
        case 'events': {
          const incoming = msg.data as FeedEvent[]
          publish(incoming, msg.mode)
          if (pausedRef.current) {
            pending.current = mergeEvents(pending.current, incoming)
            setPendingCount(pending.current.length)
          } else {
            dispatch({ type: 'events', events: incoming })
          }
          break
        }
        case 'alert':
          dispatch({ type: 'alert', value: msg.data as RisingRepo })
          break
        case 'kpi':
          dispatch({ type: 'data', key: 'metrics', value: msg.data })
          break
        default:
          dispatch({ type: 'data', key: msg.type as 'activity', value: msg.data })
      }
    }

    const connect = () => {
      dispatch({ type: 'connection', value: attempt === 0 ? 'connecting' : 'reconnecting' })
      let ws: WebSocket
      try {
        ws = new WebSocket(wsUrl())
      } catch {
        timer = window.setTimeout(connect, backoffMs(++attempt))
        return
      }
      socket.current = ws
      ws.onopen = () => {
        const wasReconnect = attempt > 0
        attempt = 0
        dispatch({ type: 'connection', value: 'open' })
        subscribe()
        if (wasReconnect) void loadSnapshot()
      }
      ws.onmessage = (e) => {
        try {
          handle(JSON.parse(e.data as string) as WsEnvelope)
        } catch {
          /* ignore malformed frames */
        }
      }
      ws.onerror = () => ws.close()
      ws.onclose = () => {
        if (closed) return
        dispatch({ type: 'connection', value: 'reconnecting' })
        timer = window.setTimeout(connect, backoffMs(++attempt))
      }
    }

    const onOnline = () => {
      if (socket.current?.readyState !== WebSocket.OPEN) {
        window.clearTimeout(timer)
        attempt = 0
        connect()
      }
    }
    window.addEventListener('online', onOnline)
    connect()
    return () => {
      closed = true
      window.clearTimeout(timer)
      window.removeEventListener('online', onOnline)
      socket.current?.close()
    }
  }, [loadSnapshot, subscribe, publish])

  // REST polling fallback while the socket is down.
  useEffect(() => {
    if (state.connection === 'open') return
    const id = window.setInterval(() => void loadSnapshot(), 10_000)
    return () => window.clearInterval(id)
  }, [state.connection, loadSnapshot])

  const setPaused = useCallback((value: boolean) => {
    pausedRef.current = value
    setPausedState(value)
    if (!value && pending.current.length > 0) {
      dispatch({ type: 'events', events: pending.current })
      pending.current = []
      setPendingCount(0)
    }
  }, [])

  const setRange = useCallback((range: RangeCode) => {
    dispatch({ type: 'context', mode: ctx.current.mode, range })
  }, [])

  const launchDemo = useCallback(async () => {
    try {
      await api.startDemo()
    } catch {
      /* the WebSocket subscribe also starts the simulator */
    }
    dispatch({ type: 'context', mode: 'demo', range: ctx.current.range })
  }, [])

  const returnToLive = useCallback(() => {
    dispatch({ type: 'context', mode: 'live', range: ctx.current.range })
  }, [])

  const dismissAlert = useCallback(() => dispatch({ type: 'alert', value: undefined }), [])

  return { state, paused, pendingCount, setPaused, setRange, launchDemo, returnToLive, dismissAlert, subscribeEvents }
}
