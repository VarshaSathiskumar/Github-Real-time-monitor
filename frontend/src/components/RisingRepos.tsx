import { motion } from 'framer-motion'
import { ExternalLink, Rocket, TrendingUp } from 'lucide-react'
import { formatCount, formatPercent } from '@/lib/format'
import type { Mode, Rising, RisingRepo } from '@/lib/types'
import { cn } from '@/lib/utils'
import { EmptyState } from './EmptyState'
import { InfoTip } from './InfoTip'
import { SectionHeader } from './SectionHeader'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'

const LEVELS: Record<RisingRepo['level'], { label: string; bars: number; color: string }> = {
  BURST: { label: 'New burst', bars: 2, color: '#0e7490' },
  WARMING: { label: 'Warming up', bars: 2, color: '#d97706' },
  RISING: { label: 'Rising', bars: 3, color: '#ea580c' },
  SURGING: { label: 'Surging', bars: 4, color: '#e11d48' },
}

function Momentum({ level }: { level: RisingRepo['level'] }) {
  const l = LEVELS[level]
  return (
    <span className="inline-flex items-end gap-[3px]" title={`Momentum: ${l.label}`} aria-label={`Momentum: ${l.label}`}>
      {[0, 1, 2, 3].map((i) => (
        <span
          key={i}
          className={cn('w-1 rounded-sm', i >= l.bars && 'opacity-20')}
          style={{ height: 6 + i * 4, background: l.color }}
        />
      ))}
    </span>
  )
}

export function RisingRepos({ data, mode }: { data?: Rising; mode: Mode }) {
  return (
    <Card className="h-full">
      <SectionHeader
        title="Rising Repositories"
        mode={mode}
        description="Momentum detector: repositories whose event rate jumped far above their recent baseline."
        actions={
          <InfoTip label="How spikes are detected">
            Kafka Streams counts events per repository in {data?.windowMinutes ?? 5} minute windows and compares the
            current window with the mean of the previous {data?.baselineWindows ?? 6}. A spike needs at least{' '}
            {data?.minEvents ?? 8} events and 3x the baseline. When the baseline is almost zero no percentage is
            shown, because it would be misleading.
          </InfoTip>
        }
      />
      <CardContent>
        {!data ? (
          <ul className="space-y-2.5">
            {Array.from({ length: 3 }, (_, i) => (
              <li key={i}>
                <Skeleton className="h-[74px] w-full" />
              </li>
            ))}
          </ul>
        ) : data.repositories.length === 0 ? (
          <EmptyState icon={Rocket} title="No activity spikes right now">
            A repository shows up here when it suddenly gets much busier than usual. Spike detection needs several
            minutes of baseline history after a restart.
          </EmptyState>
        ) : (
          <ul className="space-y-2.5">
            {data.repositories.map((r, i) => {
              const level = LEVELS[r.level]
              return (
                <motion.li
                  key={`${r.repo}-${r.windowStart}`}
                  layout="position"
                  initial={{ opacity: 0, scale: 0.97 }}
                  animate={{ opacity: 1, scale: 1 }}
                  transition={{ delay: i * 0.04 }}
                  className="rounded-xl border border-slate-900/[0.08] bg-slate-900/[0.03] p-3"
                  style={{ boxShadow: `inset 3px 0 0 ${level.color}` }}
                >
                  <div className="flex items-start justify-between gap-3">
                    <a
                      href={r.repoUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="group inline-flex min-w-0 items-center gap-1.5 text-sm font-semibold text-slate-900 hover:text-pulse-cyan"
                    >
                      <span className="truncate">{r.repo}</span>
                      <ExternalLink className="h-3 w-3 shrink-0 text-slate-500 group-hover:text-pulse-cyan" aria-label="Opens on GitHub" />
                    </a>
                    <div className="flex shrink-0 items-center gap-2">
                      <Momentum level={r.level} />
                      {r.pctIncrease != null ? (
                        <Badge variant="up" className="num">
                          <TrendingUp className="h-3 w-3" aria-hidden />+{formatPercent(r.pctIncrease)}
                        </Badge>
                      ) : (
                        <Badge variant="info" title={r.note}>
                          No baseline
                        </Badge>
                      )}
                    </div>
                  </div>
                  <dl className="mt-2 grid grid-cols-3 gap-2 text-xs">
                    <div>
                      <dt className="text-slate-500">Current</dt>
                      <dd className="num font-medium text-slate-900">
                        {formatCount(r.currentCount)} <span className="text-slate-500">/ {r.windowMinutes}m</span>
                      </dd>
                    </div>
                    <div>
                      <dt className="text-slate-500">Baseline</dt>
                      <dd className="num font-medium text-slate-800">
                        {r.baseline.toFixed(1)} <span className="text-slate-500">/ {r.windowMinutes}m</span>
                      </dd>
                    </div>
                    <div>
                      <dt className="text-slate-500">Momentum</dt>
                      <dd className="font-medium" style={{ color: level.color }}>
                        {level.label}
                      </dd>
                    </div>
                  </dl>
                </motion.li>
              )
            })}
          </ul>
        )}
        <p className="mt-3 text-[11px] leading-relaxed text-slate-500">{data?.disclaimer}</p>
      </CardContent>
    </Card>
  )
}
