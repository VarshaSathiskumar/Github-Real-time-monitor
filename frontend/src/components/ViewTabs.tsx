import { motion } from 'framer-motion'
import { BarChart3, Network, type LucideIcon } from 'lucide-react'
import type { View } from '@/lib/types'
import { cn } from '@/lib/utils'

const TABS: { view: View; label: string; icon: LucideIcon }[] = [
  { view: 'graph', label: 'Network Graph', icon: Network },
  { view: 'analytics', label: 'Analytics', icon: BarChart3 },
]

export function ViewTabs({ view, onChange }: { view: View; onChange: (v: View) => void }) {
  return (
    <nav aria-label="Dashboard views" className="-mb-px flex gap-1">
      {TABS.map((t) => {
        const active = view === t.view
        return (
          <button
            key={t.view}
            type="button"
            onClick={() => onChange(t.view)}
            aria-current={active ? 'page' : undefined}
            className={cn(
              'relative inline-flex items-center gap-2 px-3 py-2.5 text-sm font-medium transition-colors',
              active ? 'text-white' : 'text-slate-400 hover:text-slate-100',
            )}
          >
            <t.icon className="h-4 w-4" aria-hidden />
            {t.label}
            {active && (
              <motion.span
                layoutId="view-tab-underline"
                className="absolute inset-x-2 bottom-0 h-0.5 rounded-full bg-gradient-to-r from-pulse-cyan via-pulse-blue to-pulse-violet"
              />
            )}
          </button>
        )
      })}
    </nav>
  )
}
