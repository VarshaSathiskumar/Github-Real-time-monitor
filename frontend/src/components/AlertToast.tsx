import { AnimatePresence, motion } from 'framer-motion'
import { Rocket, X } from 'lucide-react'
import { useEffect } from 'react'
import type { RisingRepo } from '@/lib/types'

/** Short-lived notice when the momentum detector flags a new spike. */
export function AlertToast({ alert, onDismiss }: { alert?: RisingRepo; onDismiss: () => void }) {
  useEffect(() => {
    if (!alert) return
    const id = window.setTimeout(onDismiss, 7000)
    return () => window.clearTimeout(id)
  }, [alert, onDismiss])

  return (
    <div className="pointer-events-none fixed bottom-4 right-4 z-50 w-[min(92vw,22rem)]" aria-live="polite">
      <AnimatePresence>
        {alert && (
          <motion.div
            key={`${alert.repo}-${alert.windowStart}`}
            initial={{ opacity: 0, y: 20, scale: 0.96 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 10 }}
            className="glass pointer-events-auto flex items-start gap-3 rounded-2xl border-amber-400/30 p-3.5"
          >
            <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-amber-400/15 text-amber-700">
              <Rocket className="h-4 w-4" aria-hidden />
            </span>
            <div className="min-w-0 flex-1 text-sm">
              <p className="font-display font-semibold text-slate-900">Activity spike detected</p>
              <p className="truncate text-slate-700">
                {alert.repo}: {alert.currentCount} events in {alert.windowMinutes} min
              </p>
            </div>
            <button
              type="button"
              onClick={onDismiss}
              aria-label="Dismiss"
              className="rounded-md p-1 text-slate-600 hover:bg-slate-900/[0.06] hover:text-slate-900"
            >
              <X className="h-4 w-4" />
            </button>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}
