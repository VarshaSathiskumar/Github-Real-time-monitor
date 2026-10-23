import { motion } from 'framer-motion'
import {
  Activity,
  ArrowDownRight,
  ArrowUpRight,
  FolderGit2,
  GitFork,
  GitPullRequest,
  Minus,
  Upload,
  type LucideIcon,
} from 'lucide-react'
import { formatPercent } from '@/lib/format'
import type { Kpi, Metrics, RangeCode } from '@/lib/types'
import { cn } from '@/lib/utils'
import { AnimatedNumber } from './AnimatedNumber'
import { InfoTip } from './InfoTip'
import { Sparkline } from './Sparkline'
import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'

interface Def {
  key: keyof Pick<Metrics, 'totalEvents' | 'activeRepositories' | 'pushEvents' | 'pullRequests' | 'forks'>
  label: string
  icon: LucideIcon
  color: string
  help: string
}

const DEFS: Def[] = [
  {
    key: 'totalEvents',
    label: 'Total Events',
    icon: Activity,
    color: '#0e7490',
    help: 'Every GitHub public event observed during the selected window.',
  },
  {
    key: 'activeRepositories',
    label: 'Active Repositories',
    icon: FolderGit2,
    color: '#2563eb',
    help: 'Unique repositories that generated at least one event in the window.',
  },
  {
    key: 'pushEvents',
    label: 'Push Events',
    icon: Upload,
    color: '#4f46e5',
    help: 'Number of PushEvents observed. One push can contain many commits.',
  },
  {
    key: 'pullRequests',
    label: 'Pull Requests',
    icon: GitPullRequest,
    color: '#7c3aed',
    help: 'Pull request events observed (opened, closed, merged, updated and so on).',
  },
  {
    key: 'forks',
    label: 'Forks',
    icon: GitFork,
    color: '#059669',
    help: 'Fork events observed: repositories being copied by other users.',
  },
]

function Delta({ kpi, range }: { kpi: Kpi; range: RangeCode }) {
  if (kpi.value == null) return null
  if (kpi.changePct == null) {
    return (
      <Tooltip>
        <TooltipTrigger asChild>
          <Badge className="cursor-help">
            <Minus className="h-3 w-3" aria-hidden />
            n/a
          </Badge>
        </TooltipTrigger>
        <TooltipContent>
          No change shown yet: at least a few minutes of earlier data are needed to compare against.
        </TooltipContent>
      </Tooltip>
    )
  }
  const up = kpi.changePct >= 0
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Badge variant={up ? 'up' : 'down'} className="cursor-help">
          {up ? <ArrowUpRight className="h-3 w-3" aria-hidden /> : <ArrowDownRight className="h-3 w-3" aria-hidden />}
          {formatPercent(kpi.changePct)}
        </Badge>
      </TooltipTrigger>
      <TooltipContent>{kpi.changeBasis ?? `Change compared with the previous equivalent ${range} window.`}</TooltipContent>
    </Tooltip>
  )
}

function KpiCard({
  def,
  kpi,
  range,
  index,
}: {
  def: Def
  kpi?: Kpi
  range: RangeCode
  index: number
}) {
  const Icon = def.icon
  return (
    <motion.div
      initial={{ opacity: 0, y: 14 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: index * 0.05, duration: 0.45, ease: [0.16, 1, 0.3, 1] }}
    >
      <Card className="group relative h-full overflow-hidden p-4 sm:p-5">
        <div className="relative flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span
              className="grid h-8 w-8 place-items-center rounded-lg border border-slate-900/10"
              style={{ background: `${def.color}14`, color: def.color }}
            >
              <Icon className="h-4 w-4" aria-hidden />
            </span>
            <span className="text-[13px] font-medium text-slate-700">{def.label}</span>
          </div>
          <div className="flex items-center gap-1.5">
            <InfoTip label={`About ${def.label}`}>{def.help}</InfoTip>
          </div>
        </div>

        {!kpi ? (
          <div className="relative mt-4 space-y-3">
            <Skeleton className="h-9 w-28" />
            <Skeleton className="h-8 w-full" />
          </div>
        ) : kpi.value == null ? (
          <div className="relative mt-4">
            <div className="num text-3xl font-semibold text-slate-500 sm:text-[34px]">N/A</div>
            <p className="mt-2 text-xs leading-relaxed text-muted-foreground">{kpi.note}</p>
          </div>
        ) : (
          <div className="relative mt-3">
            <div className="flex items-end justify-between gap-2">
              <div className="num text-3xl font-semibold leading-none text-slate-900 sm:text-[34px] xl:text-[28px] 2xl:text-[32px]">
                <AnimatedNumber value={kpi.value} />
              </div>
              <Delta kpi={kpi} range={range} />
            </div>
            <Sparkline data={kpi.sparkline} color={def.color} className={cn('mt-3 h-9 w-full')} />
            {kpi.note && <p className="mt-1 text-[11px] leading-snug text-muted-foreground">{kpi.note}</p>}
          </div>
        )}
      </Card>
    </motion.div>
  )
}

export function KpiCards({ metrics, range }: { metrics?: Metrics; range: RangeCode }) {
  return (
    <section aria-label="Key metrics" className="grid grid-cols-1 gap-3 min-[480px]:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
      {DEFS.map((def, i) => (
        <KpiCard key={def.key} def={def} kpi={metrics?.[def.key]} range={range} index={i} />
      ))}
    </section>
  )
}
