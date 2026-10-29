import { useCallback, useEffect, useRef, useState } from 'react'
import { IDLE_PAUSE_MS } from '@/lib/config'

const INTERACTIONS = ['pointerdown', 'pointermove', 'keydown', 'wheel', 'touchstart'] as const

/**
 * Tells the lite deployment whether refreshing is worth a function invocation: only while the tab is
 * visible and was used within IDLE_PAUSE_MS. Coming back (or interacting again) calls onResume once.
 */
export function useActivity(enabled: boolean, onResume: () => void) {
  const lastActive = useRef(Date.now())
  const idleRef = useRef(false)
  const [idle, setIdle] = useState(false)
  const resume = useRef(onResume)
  resume.current = onResume

  const setIdleState = useCallback((value: boolean) => {
    idleRef.current = value
    setIdle(value)
  }, [])

  useEffect(() => {
    if (!enabled) return
    const touch = () => {
      lastActive.current = Date.now()
      if (idleRef.current) {
        setIdleState(false)
        resume.current()
      }
    }
    const onVisible = () => {
      if (document.visibilityState === 'visible') {
        touch()
        resume.current()
      }
    }
    for (const e of INTERACTIONS) window.addEventListener(e, touch, { passive: true })
    document.addEventListener('visibilitychange', onVisible)
    const timer = window.setInterval(() => {
      if (Date.now() - lastActive.current > IDLE_PAUSE_MS) setIdleState(true)
    }, 15_000)
    return () => {
      for (const e of INTERACTIONS) window.removeEventListener(e, touch)
      document.removeEventListener('visibilitychange', onVisible)
      window.clearInterval(timer)
    }
  }, [enabled, setIdleState])

  /** True when a refresh should be skipped right now. */
  const paused = useCallback(() => document.hidden || idleRef.current, [])
  return { idle, paused }
}
