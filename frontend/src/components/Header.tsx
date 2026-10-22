import { Github, Radio, WifiOff } from 'lucide-react'
import { useNow } from '@/hooks/useNow'
import { GITHUB_REPO_URL } from '@/lib/config'
import { timeAgo } from '@/lib/format'
import type { ConnectionState, Mode } from '@/lib/types'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'

interface Props {
  mode: Mode
  connection: ConnectionState
  lastUpdated?: number
  ingestionStatus?: string
}

function LiveIndicator({ mode, connected }: { mode: Mode; connected: boolean }) {
  const demo = mode === 'demo'
  const color = demo ? 'bg-violet-400' : connected ? 'bg-emerald-400' : 'bg-amber-400'
  const text = demo ? 'text-violet-200' : connected ? 'text-emerald-300' : 'text-amber-200'
  const ring = demo ? 'border-violet-400/30 bg-violet-500/10' : connected ? 'border-emerald-400/25 bg-emerald-400/10' : 'border-amber-400/25 bg-amber-400/10'
  return (
    <span
      className={cn('inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-semibold tracking-wider', ring, text)}
      role="status"
    >
      <span className="relative flex h-2 w-2">
        {connected && <span className={cn('absolute inline-flex h-full w-full animate-ping2 rounded-full', color)} />}
        <span className={cn('relative inline-flex h-2 w-2 rounded-full', color)} />
      </span>
      {demo ? 'DEMO DATA' : connected ? 'LIVE' : 'OFFLINE'}
    </span>
  )
}

const connectionLabel: Record<ConnectionState, string> = {
  open: 'Connected',
  connecting: 'Connecting',
  reconnecting: 'Reconnecting',
}

export function Header({ mode, connection, lastUpdated, ingestionStatus }: Props) {
  const now = useNow(1000)
  const connected = connection === 'open'
  return (
    <header className="sticky top-0 z-40 border-b border-white/[0.07] bg-background/70 backdrop-blur-xl">
      <div className="mx-auto flex max-w-[1320px] flex-wrap items-center justify-between gap-x-6 gap-y-3 px-4 py-3 sm:px-6">
        <div className="flex items-center gap-3">
          <div className="grid h-10 w-10 place-items-center rounded-xl bg-gradient-to-br from-pulse-cyan via-pulse-blue to-pulse-violet shadow-[0_8px_24px_-8px_rgba(79,140,255,0.8)]">
            <Radio className="h-5 w-5 text-white" aria-hidden />
          </div>
          <div>
            <h1 className="font-display text-xl font-bold leading-none tracking-tight text-white sm:text-2xl">
              GitHub<span className="text-gradient">Pulse</span>
            </h1>
            <p className="mt-1 text-xs text-muted-foreground sm:text-[13px]">
              Real-time intelligence from GitHub&apos;s public activity.
            </p>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2 sm:gap-3">
          <LiveIndicator mode={mode} connected={connected} />
          <div className="hidden text-xs text-muted-foreground sm:block" aria-live="off">
            Updated <span className="num text-slate-200">{lastUpdated ? timeAgo(lastUpdated, now) : 'never'}</span>
          </div>
          <span
            className={cn(
              'inline-flex items-center gap-1.5 text-xs',
              connected ? 'text-slate-300' : 'text-amber-200',
            )}
          >
            {connected ? (
              <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />
            ) : (
              <WifiOff className="h-3.5 w-3.5" aria-hidden />
            )}
            {connectionLabel[connection]}
            {mode === 'live' && ingestionStatus && ingestionStatus !== 'HEALTHY' && (
              <span className="text-amber-200">, ingestion {ingestionStatus.toLowerCase().replace('_', ' ')}</span>
            )}
          </span>
          <Button asChild variant="outline" size="sm">
            <a href={GITHUB_REPO_URL} target="_blank" rel="noopener noreferrer">
              <Github className="h-4 w-4" aria-hidden />
              View Source on GitHub
            </a>
          </Button>
        </div>
      </div>
    </header>
  )
}
