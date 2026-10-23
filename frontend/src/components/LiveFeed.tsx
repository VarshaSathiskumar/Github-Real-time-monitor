import { AnimatePresence, motion } from 'framer-motion'
import {
  CircleDot,
  ExternalLink,
  GitFork,
  GitPullRequest,
  MessageSquare,
  Pause,
  Play,
  Radio,
  Star,
  Upload,
  Zap,
  type LucideIcon,
} from 'lucide-react'
import { useMemo, useState } from 'react'
import { useNow } from '@/hooks/useNow'
import { FEED_VISIBLE } from '@/lib/feed'
import { KIND_META, timeAgo } from '@/lib/format'
import type { EventKind, FeedEvent, Mode } from '@/lib/types'
import { cn } from '@/lib/utils'
import { EmptyState } from './EmptyState'
import { SectionHeader } from './SectionHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'

export const ICONS: Record<EventKind, LucideIcon> = {
  PUSH: Upload,
  PULL_REQUEST: GitPullRequest,
  FORK: GitFork,
  WATCH: Star,
  ISSUES: CircleDot,
  ISSUE_COMMENT: MessageSquare,
  OTHER: Zap,
}

const FILTERS: { key: EventKind | 'ALL'; label: string }[] = [
  { key: 'ALL', label: 'All' },
  { key: 'PUSH', label: 'Push' },
  { key: 'PULL_REQUEST', label: 'PRs' },
  { key: 'FORK', label: 'Forks' },
  { key: 'WATCH', label: 'Stars' },
  { key: 'ISSUES', label: 'Issues' },
  { key: 'ISSUE_COMMENT', label: 'Comments' },
  { key: 'OTHER', label: 'Other' },
]

interface Props {
  events: FeedEvent[]
  loaded: boolean
  mode: Mode
  paused: boolean
  pendingCount: number
  onPausedChange: (p: boolean) => void
}

function Row({ event, now }: { event: FeedEvent; now: number }) {
  const Icon = ICONS[event.kind]
  const meta = KIND_META[event.kind]
  return (
    <motion.li
      layout="position"
      initial={{ opacity: 0, y: -14, backgroundColor: `${meta.color}33` }}
      animate={{ opacity: 1, y: 0, backgroundColor: 'rgba(255,255,255,0.0)' }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
      className="flex items-center gap-3 rounded-xl border border-transparent px-2.5 py-2.5 hover:border-white/[0.08] hover:bg-white/[0.04]"
    >
      <span
        className="grid h-8 w-8 shrink-0 place-items-center rounded-lg"
        style={{ background: `${meta.color}1f`, color: meta.color }}
      >
        <Icon className="h-4 w-4" aria-hidden />
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm text-slate-200">
          <a
            href={`https://github.com/${event.actor}`}
            target="_blank"
            rel="noopener noreferrer"
            className="font-medium text-white hover:text-pulse-cyan"
          >
            {event.actor}
          </a>{' '}
          {event.description.slice(event.actor.length).replace(event.repo, '').trimEnd()}{' '}
          <a
            href={event.repoUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="font-medium text-pulse-cyan/90 hover:text-pulse-cyan hover:underline"
          >
            {event.repo}
          </a>
        </p>
        <p className="mt-0.5 flex items-center gap-2 text-[11px] text-slate-500">
          <time dateTime={event.createdAt} title={new Date(event.createdAt).toLocaleString()}>
            {timeAgo(event.createdAt, now)}
          </time>
        </p>
      </div>
      <Badge className="hidden shrink-0 sm:inline-flex" style={{ color: meta.color, borderColor: `${meta.color}44`, background: `${meta.color}12` }}>
        {meta.short}
      </Badge>
      {event.url && (
        <a
          href={event.url}
          target="_blank"
          rel="noopener noreferrer"
          aria-label={`Open ${event.repo} event on GitHub`}
          className="shrink-0 rounded-md p-1.5 text-slate-500 hover:bg-white/10 hover:text-white"
        >
          <ExternalLink className="h-3.5 w-3.5" />
        </a>
      )}
    </motion.li>
  )
}

export function LiveFeed({ events, loaded, mode, paused, pendingCount, onPausedChange }: Props) {
  const [filter, setFilter] = useState<EventKind | 'ALL'>('ALL')
  const now = useNow(1000)

  const visible = useMemo(
    () => (filter === 'ALL' ? events : events.filter((e) => e.kind === filter)).slice(0, FEED_VISIBLE),
    [events, filter],
  )

  return (
    <Card className="h-full">
      <SectionHeader
        title="Live Activity Feed"
        mode={mode}
        description={`The latest ${FEED_VISIBLE} processed events. Event time is when GitHub says it happened; the API reports with a delay.`}
        actions={
          <Button
            variant={paused ? 'primary' : 'outline'}
            size="sm"
            onClick={() => onPausedChange(!paused)}
            aria-pressed={paused}
          >
            {paused ? <Play className="h-3.5 w-3.5" aria-hidden /> : <Pause className="h-3.5 w-3.5" aria-hidden />}
            {paused ? (pendingCount > 0 ? `Resume (${pendingCount} new)` : 'Resume') : 'Pause'}
          </Button>
        }
      />
      <CardContent>
        <div className="mb-3 flex flex-wrap gap-1.5" role="group" aria-label="Filter by event type">
          {FILTERS.map((f) => (
            <button
              key={f.key}
              type="button"
              onClick={() => setFilter(f.key)}
              aria-pressed={filter === f.key}
              className={cn(
                'rounded-full border px-2.5 py-1 text-[11px] font-medium transition-colors',
                filter === f.key
                  ? 'border-white/20 bg-white/15 text-white'
                  : 'border-white/[0.07] text-slate-400 hover:text-slate-100',
              )}
            >
              {f.label}
            </button>
          ))}
        </div>

        {!loaded ? (
          <ul className="space-y-2">
            {Array.from({ length: 6 }, (_, i) => (
              <li key={i}>
                <Skeleton className="h-12 w-full" />
              </li>
            ))}
          </ul>
        ) : visible.length === 0 ? (
          <EmptyState icon={Radio} title={filter === 'ALL' ? 'No events yet' : 'No events of this type yet'}>
            {filter === 'ALL'
              ? 'New events appear here the moment they are processed.'
              : 'Try another filter, or wait for more events to arrive.'}
          </EmptyState>
        ) : (
          <ul className="scroll-thin max-h-[640px] space-y-0.5 overflow-y-auto pr-1" aria-live="off">
            <AnimatePresence initial={false}>
              {visible.map((e) => (
                <Row key={e.id} event={e} now={now} />
              ))}
            </AnimatePresence>
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
