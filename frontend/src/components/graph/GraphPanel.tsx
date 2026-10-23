import { ExternalLink, GitBranch, MousePointerClick, User, X, Zap, type LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { useNow } from '@/hooks/useNow'
import { formatClock, formatCount, KIND_META, timeAgo } from '@/lib/format'
import type { DevNode, EventNode, GraphStore, RepoNode } from '@/lib/graph'
import type { Mode } from '@/lib/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ICONS } from '../LiveFeed'
import { GRAPH_COLORS } from './colors'

interface Props {
  store: GraphStore
  mode: Mode
  selectedId: string | null
  expanded: ReadonlySet<string>
  onSelect: (id: string | null) => void
  onCollapse: (id: string) => void
  onClose: () => void
}

type NodeKind = 'repo' | 'dev' | 'event'

export const NODE_META: Record<NodeKind, { label: string; plural: string; icon: LucideIcon; color: string; shape: string }> = {
  repo: { label: 'Repository', plural: 'Repositories', icon: GitBranch, color: GRAPH_COLORS.repo, shape: 'rounded-[4px]' },
  dev: { label: 'Developer', plural: 'Developers', icon: User, color: GRAPH_COLORS.dev, shape: 'rounded-full' },
  event: { label: 'Event', plural: 'Events', icon: Zap, color: GRAPH_COLORS.event, shape: 'rotate-45 rounded-[2px] scale-[0.8]' },
}

/** Small swatch that matches the node's colour and shape on the canvas. */
export function NodeSwatch({ type, className = '' }: { type: NodeKind; className?: string }) {
  const m = NODE_META[type]
  return <span aria-hidden className={`inline-block h-2.5 w-2.5 shrink-0 ${m.shape} ${className}`} style={{ background: m.color }} />
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-2">
      <h3 className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">{title}</h3>
      {children}
    </section>
  )
}

function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="rounded-xl border border-white/[0.07] bg-white/[0.03] px-3 py-2">
      <p className="num text-lg font-semibold leading-tight text-white">{value}</p>
      <p className="text-[11px] text-slate-500">{label}</p>
    </div>
  )
}

function LinkRow({
  type,
  label,
  meta,
  onClick,
}: {
  type: NodeKind
  label: string
  meta?: string
  onClick: () => void
}) {
  return (
    <li>
      <button
        type="button"
        onClick={onClick}
        className="flex w-full items-center gap-2.5 rounded-lg px-2 py-1.5 text-left text-sm text-slate-200 transition-colors hover:bg-white/[0.07]"
      >
        <NodeSwatch type={type} />
        <span className="min-w-0 flex-1 truncate">{label}</span>
        {meta && <span className="num shrink-0 text-xs text-slate-500">{meta}</span>}
      </button>
    </li>
  )
}

function EventRow({ node, now, onClick }: { node: EventNode; now: number; onClick: () => void }) {
  const e = node.event
  const Icon = ICONS[e.kind]
  const meta = KIND_META[e.kind]
  return (
    <li>
      <button
        type="button"
        onClick={onClick}
        className="flex w-full items-start gap-2.5 rounded-lg px-2 py-1.5 text-left transition-colors hover:bg-white/[0.07]"
      >
        <span
          className="mt-0.5 grid h-6 w-6 shrink-0 place-items-center rounded-md"
          style={{ background: `${meta.color}1f`, color: meta.color }}
        >
          <Icon className="h-3.5 w-3.5" aria-hidden />
        </span>
        <span className="min-w-0 flex-1">
          <span className="line-clamp-2 text-[13px] leading-snug text-slate-200">{e.description}</span>
          <span className="text-[11px] text-slate-500">{timeAgo(e.createdAt, now)}</span>
        </span>
      </button>
    </li>
  )
}

function ExternalButton({ href, children }: { href: string; children: ReactNode }) {
  return (
    <Button asChild variant="outline" size="sm" className="w-full sm:w-auto">
      <a href={href} target="_blank" rel="noopener noreferrer">
        <ExternalLink className="h-3.5 w-3.5" aria-hidden />
        {children}
      </a>
    </Button>
  )
}

function Empty({ children }: { children: ReactNode }) {
  return <p className="px-2 text-xs text-slate-500">{children}</p>
}

function Overview({ store, mode, onSelect }: Pick<Props, 'store' | 'mode' | 'onSelect'>) {
  const top = [...store.repos.values()].sort((a, b) => b.events - a.events).slice(0, 5)
  return (
    <div className="space-y-5">
      <p className="text-sm leading-relaxed text-slate-300">
        Every node is something seen in the GitHub public events stream
        {mode === 'demo' ? ' (simulated demo data)' : ''}. Lines connect developers to the repositories they acted
        on, and events to both.
      </p>
      <Section title="Legend">
        <ul className="space-y-1.5 text-sm text-slate-300">
          {(['repo', 'dev', 'event'] as const).map((t) => (
            <li key={t} className="flex items-center gap-2.5">
              <NodeSwatch type={t} />
              <span className="flex-1">{NODE_META[t].plural}</span>
              <span className="num text-xs text-slate-500">
                {formatCount(t === 'repo' ? store.repos.size : t === 'dev' ? store.devs.size : store.events.size)}
              </span>
            </li>
          ))}
        </ul>
      </Section>
      <Section title="Most active in view">
        {top.length === 0 ? (
          <Empty>Nothing yet.</Empty>
        ) : (
          <ul>
            {top.map((r) => (
              <LinkRow key={r.id} type="repo" label={r.name} meta={`${formatCount(r.events)} events`} onClick={() => onSelect(r.id)} />
            ))}
          </ul>
        )}
      </Section>
      <Section title="How to explore">
        <ul className="space-y-1.5 text-xs leading-relaxed text-slate-400">
          <li>Scroll or pinch to zoom, drag the background to pan, drag a node to move it.</li>
          <li>Click a repository to expand its developers and recent events.</li>
          <li>Click a developer to see their activity, or an event for its details.</li>
        </ul>
      </Section>
    </div>
  )
}

function RepoDetail({ node, store, expanded, now, onSelect, onCollapse }: { node: RepoNode; now: number } & Pick<Props, 'store' | 'expanded' | 'onSelect' | 'onCollapse'>) {
  const devs = store.developersOf(node.id)
  const events = store.eventsOfRepo(node.id, 8)
  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 gap-2">
        <Stat label="events observed" value={formatCount(node.events)} />
        <Stat label="developers" value={formatCount(devs.length)} />
      </div>
      <div className="flex flex-wrap gap-2">
        <ExternalButton href={node.url}>Open on GitHub</ExternalButton>
        {expanded.has(node.id) && (
          <Button variant="ghost" size="sm" onClick={() => onCollapse(node.id)}>
            Collapse events
          </Button>
        )}
      </div>
      <Section title="Connected developers">
        {devs.length === 0 ? (
          <Empty>No developers in view.</Empty>
        ) : (
          <ul>
            {devs.slice(0, 8).map(({ dev, events: n }) => (
              <LinkRow key={dev.id} type="dev" label={dev.login} meta={`${n}`} onClick={() => onSelect(dev.id)} />
            ))}
            {devs.length > 8 && <Empty>and {devs.length - 8} more</Empty>}
          </ul>
        )}
      </Section>
      <Section title="Recent events">
        {events.length === 0 ? (
          <Empty>No recent events kept for this repository.</Empty>
        ) : (
          <ul>
            {events.map((n) => (
              <EventRow key={n.id} node={n} now={now} onClick={() => onSelect(n.id)} />
            ))}
          </ul>
        )}
      </Section>
    </div>
  )
}

function DevDetail({ node, store, now, onSelect }: { node: DevNode; now: number } & Pick<Props, 'store' | 'onSelect'>) {
  const repos = store.repositoriesOf(node.id)
  const events = store.eventsOfDev(node.id, 8)
  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 gap-2">
        <Stat label="events observed" value={formatCount(node.events)} />
        <Stat label="repositories" value={formatCount(repos.length)} />
      </div>
      <ExternalButton href={node.url}>View profile</ExternalButton>
      <Section title="Associated repositories">
        {repos.length === 0 ? (
          <Empty>No repositories in view.</Empty>
        ) : (
          <ul>
            {repos.slice(0, 8).map(({ repo, events: n }) => (
              <LinkRow key={repo.id} type="repo" label={repo.name} meta={`${n}`} onClick={() => onSelect(repo.id)} />
            ))}
          </ul>
        )}
      </Section>
      <Section title="Recent activity">
        {events.length === 0 ? (
          <Empty>No recent events kept for this developer.</Empty>
        ) : (
          <ul>
            {events.map((n) => (
              <EventRow key={n.id} node={n} now={now} onClick={() => onSelect(n.id)} />
            ))}
          </ul>
        )}
      </Section>
    </div>
  )
}

function EventDetail({ node, now, onSelect }: { node: EventNode; now: number } & Pick<Props, 'onSelect'>) {
  const e = node.event
  const meta = KIND_META[e.kind]
  const Icon = ICONS[e.kind]
  const rows: { label: string; value: ReactNode }[] = [
    { label: 'Type', value: <Badge style={{ color: meta.color, borderColor: `${meta.color}44`, background: `${meta.color}12` }}>{meta.short}</Badge> },
    {
      label: 'Time',
      value: (
        <time dateTime={e.createdAt} title={new Date(e.createdAt).toLocaleString()}>
          {formatClock(e.createdAt, true)} <span className="text-slate-500">({timeAgo(e.createdAt, now)})</span>
        </time>
      ),
    },
    {
      label: 'Actor',
      value: (
        <button type="button" onClick={() => onSelect(node.devId)} className="font-medium text-violet-200 hover:underline">
          {e.actor}
        </button>
      ),
    },
    {
      label: 'Repository',
      value: (
        <button type="button" onClick={() => onSelect(node.repoId)} className="break-all text-left font-medium text-pulse-cyan hover:underline">
          {e.repo}
        </button>
      ),
    },
  ]
  return (
    <div className="space-y-5">
      <div className="flex items-start gap-3 rounded-xl border border-white/[0.07] bg-white/[0.03] p-3">
        <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg" style={{ background: `${meta.color}1f`, color: meta.color }}>
          <Icon className="h-4 w-4" aria-hidden />
        </span>
        <p className="text-sm leading-snug text-slate-100">{e.description}</p>
      </div>
      <dl className="space-y-2.5 text-sm">
        {rows.map((r) => (
          <div key={r.label} className="flex items-baseline justify-between gap-4">
            <dt className="text-xs text-slate-500">{r.label}</dt>
            <dd className="min-w-0 text-right text-slate-200">{r.value}</dd>
          </div>
        ))}
      </dl>
      <ExternalButton href={e.url || e.repoUrl}>Open on GitHub</ExternalButton>
    </div>
  )
}

export function GraphPanel(props: Props) {
  const { store, selectedId, onClose } = props
  const now = useNow(1000)
  const node = selectedId ? store.get(selectedId) : undefined
  const type: NodeKind | null = node?.type ?? null
  const meta = type ? NODE_META[type] : null
  const title = !node ? 'Network overview' : node.type === 'repo' ? node.name : node.type === 'dev' ? node.login : `${KIND_META[node.event.kind].short} event`

  return (
    <aside aria-label="Selected node details" className="flex h-full min-h-0 flex-col">
      <header className="flex items-start gap-3 border-b border-white/[0.07] px-4 py-3.5">
        <span
          className="mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-lg"
          style={{ background: `${meta?.color ?? '#4f8cff'}22`, color: meta?.color ?? '#4f8cff' }}
        >
          {meta ? <meta.icon className="h-4 w-4" aria-hidden /> : <MousePointerClick className="h-4 w-4" aria-hidden />}
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">{meta ? meta.label : 'Nothing selected'}</p>
          <h2 className="truncate font-display text-base font-semibold text-white" title={title}>
            {title}
          </h2>
        </div>
        {node && (
          <Button variant="ghost" size="sm" className="-mr-1.5 h-8 w-8 shrink-0 px-0" onClick={onClose} aria-label="Clear selection">
            <X className="h-4 w-4" aria-hidden />
          </Button>
        )}
      </header>
      <div className="scroll-thin min-h-0 flex-1 overflow-y-auto px-4 py-4">
        {node?.type === 'repo' ? (
          <RepoDetail node={node} now={now} {...props} />
        ) : node?.type === 'dev' ? (
          <DevDetail node={node} now={now} {...props} />
        ) : node?.type === 'event' ? (
          <EventDetail node={node} now={now} onSelect={props.onSelect} />
        ) : (
          <Overview {...props} />
        )}
      </div>
    </aside>
  )
}
