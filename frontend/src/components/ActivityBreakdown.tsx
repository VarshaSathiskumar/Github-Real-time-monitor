import { PieChart as PieIcon } from 'lucide-react'
import { useState } from 'react'
import { Cell, Pie, PieChart, ResponsiveContainer } from 'recharts'
import { formatCount, KIND_META } from '@/lib/format'
import type { Breakdown, Mode, RangeCode } from '@/lib/types'
import { cn } from '@/lib/utils'
import { EmptyState } from './EmptyState'
import { SectionHeader } from './SectionHeader'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'

export function ActivityBreakdown({ data, mode, range }: { data?: Breakdown; mode: Mode; range: RangeCode }) {
  const [active, setActive] = useState<number | null>(null)
  const items = data?.items ?? []
  const chartItems = items.filter((i) => i.count > 0)
  const focus = active !== null ? chartItems[active] : undefined

  return (
    <Card className="h-full">
      <SectionHeader
        title="What Is Happening on GitHub?"
        mode={mode}
        windowLabel={`last ${range}`}
        description="Distribution of observed event types. Stars are WatchEvents."
      />
      <CardContent>
        {!data ? (
          <div className="flex flex-col items-center gap-5 sm:flex-row">
            <Skeleton className="h-44 w-44 shrink-0 rounded-full" />
            <div className="w-full space-y-2">
              {Array.from({ length: 6 }, (_, i) => (
                <Skeleton key={i} className="h-6 w-full" />
              ))}
            </div>
          </div>
        ) : data.total === 0 ? (
          <EmptyState icon={PieIcon} title="No events in this window yet" />
        ) : (
          <div className="flex flex-col items-center gap-5 sm:flex-row">
            <div className="relative h-48 w-48 shrink-0">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={chartItems}
                    dataKey="count"
                    nameKey="label"
                    innerRadius={58}
                    outerRadius={84}
                    paddingAngle={2}
                    stroke="none"
                    startAngle={90}
                    endAngle={-270}
                    onMouseEnter={(_, i) => setActive(i)}
                    onMouseLeave={() => setActive(null)}
                    isAnimationActive={false}
                  >
                    {chartItems.map((i, idx) => (
                      <Cell
                        key={i.kind}
                        fill={KIND_META[i.kind].color}
                        fillOpacity={active === null || active === idx ? 1 : 0.35}
                        style={{ transition: 'fill-opacity 150ms' }}
                      />
                    ))}
                  </Pie>
                </PieChart>
              </ResponsiveContainer>
              <div className="pointer-events-none absolute inset-0 grid place-items-center text-center">
                <div>
                  <div className="num text-2xl font-semibold text-white">
                    {formatCount(focus ? focus.count : data.total)}
                  </div>
                  <div className="max-w-[90px] text-[11px] leading-tight text-slate-400">
                    {focus ? `${focus.label}, ${focus.percent}%` : 'events'}
                  </div>
                </div>
              </div>
            </div>

            <ul className="w-full space-y-1" aria-label="Event type counts">
              {items.map((i) => {
                const idx = chartItems.findIndex((c) => c.kind === i.kind)
                return (
                  <li
                    key={i.kind}
                    onMouseEnter={() => idx >= 0 && setActive(idx)}
                    onMouseLeave={() => setActive(null)}
                    className={cn(
                      'flex items-center justify-between gap-3 rounded-lg px-2.5 py-1.5 text-sm transition-colors',
                      idx >= 0 && idx === active ? 'bg-white/10' : 'hover:bg-white/[0.05]',
                      i.count === 0 && 'opacity-50',
                    )}
                  >
                    <span className="flex min-w-0 items-center gap-2.5 text-slate-200">
                      <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: KIND_META[i.kind].color }} />
                      <span className="truncate">{i.label}</span>
                    </span>
                    <span className="flex shrink-0 items-baseline gap-2">
                      <span className="num font-medium text-white">{formatCount(i.count)}</span>
                      <span className="num w-12 text-right text-xs text-slate-400">{i.percent.toFixed(1)}%</span>
                    </span>
                  </li>
                )
              })}
            </ul>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
