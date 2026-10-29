import { Cpu, Database, GitBranch, HardDrive, Layers, Network } from 'lucide-react'
import type { ReactNode } from 'react'
import { useNow } from '@/hooks/useNow'
import { formatCount, timeAgo } from '@/lib/format'
import type { Mode, SystemStatus } from '@/lib/types'
import { cn } from '@/lib/utils'
import { Skeleton } from '@/components/ui/skeleton'

function Dot({ ok, warn }: { ok: boolean; warn?: boolean }) {
  return (
    <span
      className={cn('h-2 w-2 rounded-full', ok ? 'bg-emerald-400' : warn ? 'bg-amber-400' : 'bg-rose-400')}
      aria-hidden
    />
  )
}

function Item({ icon, label, children }: { icon: ReactNode; label: string; children: ReactNode }) {
  return (
    <div className="flex items-start gap-3 rounded-xl border border-slate-900/[0.08] bg-slate-900/[0.03] p-3">
      <span className="mt-0.5 text-slate-600">{icon}</span>
      <div className="min-w-0">
        <div className="text-[11px] uppercase tracking-wider text-slate-500">{label}</div>
        <div className="mt-0.5 text-sm text-slate-900">{children}</div>
      </div>
    </div>
  )
}

export function SystemPanel({ status, mode }: { status?: SystemStatus; mode: Mode }) {
  const now = useNow(1000)
  const ing = status?.ingestion
  const an = status?.analytics
  const lite = status?.architecture === 'serverless-lite'
  const demoLite = lite && mode === 'demo'
  return (
    <section id="pipeline-health" aria-label="Pipeline health" className="glass scroll-mt-24 rounded-2xl p-5 sm:p-6">
      <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="font-display text-lg font-semibold text-slate-900">Pipeline Health</h2>
        <span className="text-xs text-muted-foreground">
          {lite
            ? 'Serverless lite: ingestion runs on demand, no Kafka or Redis'
            : mode === 'demo'
              ? 'Showing the live pipeline'
              : 'Ingestion health arrives over gRPC'}
        </span>
      </div>
      {!status ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
          {Array.from({ length: 6 }, (_, i) => (
            <Skeleton key={i} className="h-16" />
          ))}
        </div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
          <Item icon={<Network className="h-4 w-4" />} label={lite ? 'Ingestion (on demand)' : 'Ingestion (gRPC)'}>
            {demoLite ? (
              'not used for demo data'
            ) : (
              <>
                <span className="flex items-center gap-2">
                  <Dot ok={ing?.status === 'HEALTHY'} warn={ing?.status === 'STARTING' || ing?.status === 'DEGRADED'} />
                  {ing?.status.replace('_', ' ').toLowerCase()}
                </span>
                <span className="text-xs text-slate-500">polled {timeAgo(ing?.lastSuccessfulPoll, now)}</span>
              </>
            )}
          </Item>
          <Item icon={<GitBranch className="h-4 w-4" />} label="Events ingested">
            <span className="num">{formatCount(ing?.eventsIngested ?? 0)}</span>
            <span className="block text-xs text-slate-500">
              {ing && ing.rateLimitRemaining >= 0 ? `${ing.rateLimitRemaining} API calls left` : 'rate limit unknown'}
            </span>
          </Item>
          {lite ? (
            <>
              <Item icon={<Cpu className="h-4 w-4" />} label="Aggregation">
                SQL in the function
                <span className="block text-xs text-slate-500">no stream processor</span>
              </Item>
              <Item icon={<Layers className="h-4 w-4" />} label="Redis">
                not used
              </Item>
            </>
          ) : (
            <>
              <Item icon={<Cpu className="h-4 w-4" />} label="Stream processor">
                <span className="flex items-center gap-2">
                  <Dot ok={an?.streamsState === 'RUNNING'} warn={an?.streamsState === 'REBALANCING'} />
                  {an?.streamsState.toLowerCase()}
                </span>
                <span className="text-xs text-slate-500">
                  lag {an?.consumerLag === undefined || an?.consumerLag === null ? 'n/a' : formatCount(an.consumerLag)}
                </span>
              </Item>
              <Item icon={<Layers className="h-4 w-4" />} label="Redis">
                <span className="flex items-center gap-2">
                  <Dot ok={!!an?.redis} />
                  {an?.redis ? 'up' : 'down'}
                </span>
              </Item>
            </>
          )}
          <Item icon={<Database className="h-4 w-4" />} label="PostgreSQL">
            {demoLite ? (
              'not used for demo data'
            ) : (
              <span className="flex items-center gap-2">
                <Dot ok={!!an?.postgres} />
                {an?.postgres ? 'up' : 'down'}
              </span>
            )}
          </Item>
          <Item icon={<HardDrive className="h-4 w-4" />} label="Last event observed">
            {demoLite ? 'simulated' : an?.lastEventObservedAt ? timeAgo(an.lastEventObservedAt, now) : 'none yet'}
          </Item>
        </div>
      )}
      {ing?.lastError && (
        <p className="mt-3 rounded-lg border border-amber-400/20 bg-amber-400/10 px-3 py-2 text-xs text-amber-900">
          Last ingestion error: {ing.lastError}
        </p>
      )}
      <p className="mt-4 text-[11px] leading-relaxed text-slate-500">{status?.disclaimer}</p>
    </section>
  )
}
