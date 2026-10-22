import { motion } from 'framer-motion'
import { ExternalLink, Flame, GitFork, GitPullRequest, Search, Upload } from 'lucide-react'
import { formatCount } from '@/lib/format'
import type { Mode, RangeCode, Trending } from '@/lib/types'
import { EmptyState } from './EmptyState'
import { InfoTip } from './InfoTip'
import { SectionHeader } from './SectionHeader'
import { Sparkline } from './Sparkline'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'

const RANK_COLORS = ['#fbbf24', '#cbd5e1', '#f59e0b', '#64748b', '#64748b']

export function TrendingRepos({ data, mode, range }: { data?: Trending; mode: Mode; range: RangeCode }) {
  return (
    <Card className="h-full">
      <SectionHeader
        title="Trending Repositories"
        mode={mode}
        windowLabel={`last ${range}`}
        description="The five most active repositories in the observed event stream. Not GitHub's most popular repositories."
        actions={
          <InfoTip label="How repositories are ranked">
            {data?.scoreFormula ?? 'Activity Score = Push x 2 + Pull Request x 3 + Fork x 2 + Other x 1'}. Weights
            are configurable on the server.
          </InfoTip>
        }
      />
      <CardContent>
        {!data ? (
          <ul className="space-y-3">
            {Array.from({ length: 5 }, (_, i) => (
              <li key={i}>
                <Skeleton className="h-[68px] w-full" />
              </li>
            ))}
          </ul>
        ) : data.repositories.length === 0 ? (
          <EmptyState icon={Search} title="No repository activity observed yet">
            Rankings appear once events arrive in this window.
          </EmptyState>
        ) : (
          <ol className="space-y-2.5">
            {data.repositories.map((r, i) => (
              <motion.li
                key={r.name}
                layout="position"
                initial={{ opacity: 0, x: -10 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ delay: i * 0.04, duration: 0.35 }}
                className="rounded-xl border border-white/[0.07] bg-white/[0.03] p-3 transition-colors hover:bg-white/[0.06]"
              >
                <div className="flex items-center gap-3">
                  <span
                    className="num grid h-8 w-8 shrink-0 place-items-center rounded-lg border border-white/10 text-sm font-bold"
                    style={{ color: RANK_COLORS[i], background: `${RANK_COLORS[i]}18` }}
                    aria-label={`Rank ${r.rank}`}
                  >
                    {r.rank}
                  </span>
                  <div className="min-w-0 flex-1">
                    <a
                      href={r.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="group inline-flex max-w-full items-center gap-1.5 text-sm"
                    >
                      <span className="truncate text-slate-400">{r.owner}/</span>
                      <span className="truncate font-semibold text-white group-hover:text-pulse-cyan">
                        {r.name.slice(r.owner.length + 1)}
                      </span>
                      <ExternalLink className="h-3 w-3 shrink-0 text-slate-500 group-hover:text-pulse-cyan" aria-label="Opens on GitHub" />
                    </a>
                    <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-slate-400">
                      <span title="Total observed events" className="num text-slate-200">
                        {formatCount(r.totalEvents)} events
                      </span>
                      <span className="inline-flex items-center gap-1" title="Push events">
                        <Upload className="h-3 w-3 text-pulse-blue" aria-hidden />
                        <span className="num">{formatCount(r.pushEvents)}</span>
                      </span>
                      <span className="inline-flex items-center gap-1" title="Pull request events">
                        <GitPullRequest className="h-3 w-3 text-pulse-violet" aria-hidden />
                        <span className="num">{formatCount(r.pullRequestEvents)}</span>
                      </span>
                      <span className="inline-flex items-center gap-1" title="Fork events">
                        <GitFork className="h-3 w-3 text-pulse-green" aria-hidden />
                        <span className="num">{formatCount(r.forkEvents)}</span>
                      </span>
                    </div>
                  </div>
                  <div className="hidden w-24 shrink-0 sm:block">
                    <Sparkline data={r.sparkline} color="#22d3ee" className="h-8 w-full" />
                    <span className="sr-only">Activity trend</span>
                  </div>
                  <div className="shrink-0 text-right">
                    <div className="num flex items-center justify-end gap-1 text-base font-semibold text-white">
                      <Flame className="h-3.5 w-3.5 text-amber-400" aria-hidden />
                      {formatCount(r.score)}
                    </div>
                    <div className="text-[10px] uppercase tracking-wider text-slate-500">score</div>
                  </div>
                </div>
                <div
                  className="mt-2.5 h-1.5 overflow-hidden rounded-full bg-white/[0.06]"
                  role="img"
                  aria-label={`${Math.round(r.share * 100)}% of the top score`}
                >
                  <motion.div
                    className="h-full rounded-full bg-gradient-to-r from-pulse-cyan via-pulse-blue to-pulse-violet"
                    initial={{ width: 0 }}
                    animate={{ width: `${Math.max(3, r.share * 100)}%` }}
                    transition={{ duration: 0.7, ease: [0.16, 1, 0.3, 1] }}
                  />
                </div>
              </motion.li>
            ))}
          </ol>
        )}
      </CardContent>
    </Card>
  )
}
