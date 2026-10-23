import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'

export function EmptyState({
  icon: Icon,
  title,
  children,
}: {
  icon: LucideIcon
  title: string
  children?: ReactNode
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-4 py-10 text-center">
      <span className="grid h-11 w-11 place-items-center rounded-full border border-slate-900/15 bg-slate-900/[0.03] text-slate-600">
        <Icon className="h-5 w-5" aria-hidden />
      </span>
      <p className="font-display text-sm font-semibold text-slate-800">{title}</p>
      {children && <p className="max-w-sm text-xs leading-relaxed text-muted-foreground">{children}</p>}
    </div>
  )
}
