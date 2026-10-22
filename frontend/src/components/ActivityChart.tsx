import { LineChart as ChartIcon } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip as ChartTooltip, XAxis, YAxis } from 'recharts'
import { formatClock } from '@/lib/format'
import type { Activity, Mode, RangeCode } from '@/lib/types'
import { cn } from '@/lib/utils'
import { EmptyState } from './EmptyState'
import { RangeSelector } from './Toolbar'
import { SectionHeader } from './SectionHeader'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'

type SeriesKey = 'total' | 'push' | 'pullRequest'

const SERIES: { key: SeriesKey; label: string; color: string }[] = [
  { key: 'total', label: 'Total events', color: '#22d3ee' },
  { key: 'push', label: 'Push events', color: '#4f8cff' },
  { key: 'pullRequest', label: 'Pull requests', color: '#a78bfa' },
]

interface Props {
  activity?: Activity
  mode: Mode
  range: RangeCode
  onRange: (r: RangeCode) => void
}

interface TipProps {
  active?: boolean
  label?: number
  payload?: { dataKey: string; value: number; color: string }[]
  bucketMinutes: number
}

function ChartTip({ active, label, payload, bucketMinutes }: TipProps) {
  if (!active || !payload?.length || label === undefined) return null
  return (
    <div className="rounded-xl border border-white/10 bg-popover/95 px-3 py-2 text-xs shadow-xl backdrop-blur">
      <div className="mb-1.5 font-medium text-slate-200">
        {new Date(label).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
        {bucketMinutes > 1 && <span className="text-slate-500"> ({bucketMinutes} min bucket)</span>}
      </div>
      {SERIES.map((s) => {
        const p = payload.find((x) => x.dataKey === s.key)
        if (!p) return null
        return (
          <div key={s.key} className="flex items-center justify-between gap-6 py-0.5">
            <span className="flex items-center gap-2 text-slate-300">
              <span className="h-2 w-2 rounded-full" style={{ background: s.color }} />
              {s.label}
            </span>
            <span className="num font-medium text-white">{Number(p.value).toFixed(1)}/min</span>
          </div>
        )
      })}
    </div>
  )
}

export function ActivityChart({ activity, mode, range, onRange }: Props) {
  const [hidden, setHidden] = useState<Set<SeriesKey>>(new Set())

  const data = useMemo(
    () => (activity?.points ?? []).map((p) => ({ ...p, t: new Date(p.t).getTime() })),
    [activity],
  )
  const bucket = activity?.bucketMinutes ?? 1

  const toggle = (key: SeriesKey) =>
    setHidden((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else if (next.size < SERIES.length - 1) next.add(key)
      return next
    })

  const xFormat = (t: number) => formatClock(t)

  return (
    <Card>
      <SectionHeader
        title="GitHub Activity Over Time"
        mode={mode}
        description={`Average events per minute${bucket > 1 ? ` in ${bucket} minute buckets` : ''}. Live window from Redis counters, longer ranges from PostgreSQL aggregates.`}
        actions={<RangeSelector value={range} onChange={onRange} />}
      />
      <CardContent>
        <div className="mb-3 flex flex-wrap gap-2" role="group" aria-label="Toggle series">
          {SERIES.map((s) => {
            const on = !hidden.has(s.key)
            return (
              <button
                key={s.key}
                type="button"
                aria-pressed={on}
                onClick={() => toggle(s.key)}
                className={cn(
                  'inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-medium transition-all',
                  on ? 'border-white/15 bg-white/10 text-white' : 'border-white/5 text-slate-500 hover:text-slate-300',
                )}
              >
                <span
                  className="h-2 w-2 rounded-full transition-opacity"
                  style={{ background: s.color, opacity: on ? 1 : 0.3 }}
                />
                {s.label}
              </button>
            )
          })}
        </div>

        <div className="h-[300px] w-full sm:h-[340px]">
          {!activity ? (
            <Skeleton className="h-full w-full" />
          ) : data.length < 2 ? (
            <EmptyState icon={ChartIcon} title="Not enough data to draw a trend yet">
              {mode === 'live'
                ? 'The chart fills in as events are observed, one point per minute. Launch the demo to see it with data right away.'
                : 'The simulator is warming up.'}
            </EmptyState>
          ) : (
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={data} margin={{ top: 8, right: 8, left: -12, bottom: 0 }}>
                <defs>
                  {SERIES.map((s) => (
                    <linearGradient key={s.key} id={`fill-${s.key}`} x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor={s.color} stopOpacity={0.4} />
                      <stop offset="100%" stopColor={s.color} stopOpacity={0.02} />
                    </linearGradient>
                  ))}
                </defs>
                <CartesianGrid stroke="rgba(255,255,255,0.06)" vertical={false} />
                <XAxis
                  dataKey="t"
                  type="number"
                  scale="time"
                  domain={['dataMin', 'dataMax']}
                  tickFormatter={xFormat}
                  tick={{ fill: '#8794ad', fontSize: 11 }}
                  axisLine={false}
                  tickLine={false}
                  minTickGap={48}
                />
                <YAxis
                  tick={{ fill: '#8794ad', fontSize: 11 }}
                  axisLine={false}
                  tickLine={false}
                  width={52}
                  tickFormatter={(v: number) => (v >= 1000 ? `${(v / 1000).toFixed(1)}k` : `${Math.round(v)}`)}
                  allowDecimals={false}
                />
                <ChartTooltip
                  cursor={{ stroke: 'rgba(255,255,255,0.25)', strokeDasharray: '3 3' }}
                  content={<ChartTip bucketMinutes={bucket} />}
                />
                {SERIES.filter((s) => !hidden.has(s.key)).map((s) => (
                  <Area
                    key={s.key}
                    type="monotone"
                    dataKey={s.key}
                    name={s.label}
                    stroke={s.color}
                    strokeWidth={2}
                    fill={`url(#fill-${s.key})`}
                    dot={false}
                    activeDot={{ r: 4, strokeWidth: 0 }}
                    isAnimationActive
                    animationDuration={500}
                  />
                ))}
              </AreaChart>
            </ResponsiveContainer>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
