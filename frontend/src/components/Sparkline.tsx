import { useId } from 'react'

interface Props {
  data: number[]
  color: string
  className?: string
}

/** Dependency-free SVG sparkline with a soft gradient fill. */
export function Sparkline({ data, color, className }: Props) {
  const id = useId().replace(/:/g, '')
  if (data.length < 2) {
    return <div className={className} aria-hidden />
  }
  const max = Math.max(...data, 1)
  const min = Math.min(...data, 0)
  const span = max - min || 1
  const step = 100 / (data.length - 1)
  const points = data.map((v, i) => [i * step, 30 - ((v - min) / span) * 26] as const)
  const line = points.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(2)},${y.toFixed(2)}`).join(' ')
  const area = `${line} L100,32 L0,32 Z`
  return (
    <svg viewBox="0 0 100 32" preserveAspectRatio="none" className={className} aria-hidden>
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.18" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={area} fill={`url(#${id})`} />
      <path d={line} fill="none" stroke={color} strokeWidth="1.6" vectorEffect="non-scaling-stroke" strokeLinejoin="round" />
    </svg>
  )
}
