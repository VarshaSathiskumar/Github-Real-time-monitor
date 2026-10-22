import { animate, useMotionValue, useReducedMotion } from 'framer-motion'
import { useEffect, useState } from 'react'
import { formatCount } from '@/lib/format'

/** Counts smoothly from the previous value to the next one. */
export function AnimatedNumber({ value }: { value: number }) {
  const reduce = useReducedMotion()
  const mv = useMotionValue(value)
  const [shown, setShown] = useState(value)

  useEffect(() => mv.on('change', (v) => setShown(v)), [mv])

  useEffect(() => {
    if (reduce) {
      mv.set(value)
      return
    }
    const controls = animate(mv, value, { duration: 0.9, ease: [0.16, 1, 0.3, 1] })
    return () => controls.stop()
  }, [value, reduce, mv])

  return <>{formatCount(shown)}</>
}
