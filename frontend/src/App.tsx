import { AnimatePresence } from 'framer-motion'
import { CloudOff, Info, PauseCircle } from 'lucide-react'
import { ActivityBreakdown } from '@/components/ActivityBreakdown'
import { ActivityChart } from '@/components/ActivityChart'
import { AlertToast } from '@/components/AlertToast'
import { DemoBanner } from '@/components/DemoBanner'
import { Header } from '@/components/Header'
import { KpiCards } from '@/components/KpiCards'
import { LiveFeed } from '@/components/LiveFeed'
import { RisingRepos } from '@/components/RisingRepos'
import { SystemPanel } from '@/components/SystemPanel'
import { Toolbar } from '@/components/Toolbar'
import { TrendingRepos } from '@/components/TrendingRepos'
import { TooltipProvider } from '@/components/ui/tooltip'
import { GraphView } from '@/components/graph/GraphView'
import { LITE_MODE, POLLING_MODE } from '@/lib/config'
import { useDashboard } from '@/hooks/useDashboard'
import { useGraph } from '@/hooks/useGraph'
import { useView } from '@/hooks/useView'

export default function App() {
  const { state, paused, idle, pendingCount, setPaused, setRange, returnToLive, dismissAlert, subscribeEvents } =
    useDashboard()
  const { mode, range } = state
  const [view, setView] = useView()
  // Lives here, not in the graph view, so the graph keeps filling while Analytics is open.
  const graph = useGraph(mode, subscribeEvents)

  return (
    <TooltipProvider delayDuration={150}>
      <Header
        mode={mode}
        connection={state.connection}
        lastUpdated={state.lastUpdated}
        ingestionStatus={state.status?.ingestion.status}
        view={view}
        onViewChange={setView}
      />

      <main className="mx-auto flex max-w-[1320px] flex-col gap-4 px-4 pb-16 pt-5 sm:gap-5 sm:px-6 sm:pt-6">
        {view === 'analytics' && (
          <Toolbar mode={mode} range={range} onRange={setRange} onReturnToLive={returnToLive} />
        )}

        <AnimatePresence initial={false}>{mode === 'demo' && <DemoBanner onReturn={returnToLive} />}</AnimatePresence>

        {LITE_MODE && mode === 'live' && (
          <div
            role="note"
            className="flex items-start gap-3 rounded-2xl border border-sky-300 bg-sky-50 px-4 py-3 text-xs text-sky-950"
          >
            <Info className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <p>
              <strong className="font-semibold">Serverless lite deployment.</strong> The main architecture, available
              on GitHub, uses Java, Spring Boot, Apache Kafka, Kafka Streams, Redis, PostgreSQL, and WebSockets for
              real-time event processing and analytics. This live demo uses a lightweight serverless deployment on
              Vercel and Neon PostgreSQL, with on-demand GitHub event ingestion and REST polling. Performance
              benchmarks are based on the full Docker Compose architecture.
            </p>
          </div>
        )}

        {view === 'analytics' && (
          <div
            role="note"
            className="flex items-start gap-3 rounded-2xl border border-sky-300 bg-sky-50 px-4 py-3 text-xs text-sky-950"
          >
            <Info className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <p>
              <strong className="font-semibold">Why do different time ranges show the same numbers?</strong> The 15 min,
              1 hour, 6 hour and 24 hour windows overlap and only count events observed since collection started
              (there is no backfill from GitHub). Until more history than the selected range has accumulated, longer
              ranges contain the same events as shorter ones.
            </p>
          </div>
        )}

        {LITE_MODE && idle && (
          <div
            role="status"
            className="flex items-start gap-3 rounded-2xl border border-amber-400/25 bg-amber-400/10 px-4 py-3 text-sm text-amber-900"
          >
            <PauseCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <p>Live refresh is paused after a period without activity. Move the mouse or press a key to resume.</p>
          </div>
        )}

        {state.loadFailed && !state.metrics && (
          <div
            role="alert"
            className="flex items-start gap-3 rounded-2xl border border-amber-400/25 bg-amber-400/10 px-4 py-3 text-sm text-amber-900"
          >
            <CloudOff className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <p>
              Cannot reach the analytics service right now. Retrying automatically. You can still launch the demo
              once the service is reachable.
            </p>
          </div>
        )}

        {view === 'graph' ? (
          <GraphView graph={graph} mode={mode} loaded={state.eventsLoaded} />
        ) : (
          <>
            <KpiCards metrics={state.metrics} range={range} />

            <ActivityChart activity={state.activity} mode={mode} range={range} onRange={setRange} />

            <div className="grid gap-4 sm:gap-5 lg:grid-cols-12">
              <div className="lg:col-span-7">
                <TrendingRepos data={state.trending} mode={mode} range={range} />
              </div>
              <div className="lg:col-span-5">
                <ActivityBreakdown data={state.breakdown} mode={mode} range={range} />
              </div>
            </div>

            <div className="grid gap-4 sm:gap-5 lg:grid-cols-12">
              <div className="lg:col-span-7">
                <LiveFeed
                  events={state.events}
                  loaded={state.eventsLoaded}
                  mode={mode}
                  paused={paused}
                  pendingCount={pendingCount}
                  onPausedChange={setPaused}
                />
              </div>
              <div className="lg:col-span-5">
                <RisingRepos data={state.rising} mode={mode} />
              </div>
            </div>

            <SystemPanel status={state.status} mode={mode} />
          </>
        )}

        <footer className="pt-2 text-center text-xs text-slate-500">
          GitHubPulse. Data from GitHub&apos;s public events API, not affiliated with or endorsed by GitHub.
          {LITE_MODE
            ? ' Serverless lite deployment (Vercel Functions and Neon PostgreSQL). The full Kafka based architecture runs locally with Docker Compose.'
            : POLLING_MODE && ' This deployed demo uses REST polling to refresh analytics data.'}
        </footer>
      </main>

      <AlertToast alert={state.alert} onDismiss={dismissAlert} />
    </TooltipProvider>
  )
}
