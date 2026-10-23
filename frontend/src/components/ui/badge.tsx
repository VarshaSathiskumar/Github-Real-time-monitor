import { cva, type VariantProps } from 'class-variance-authority'
import * as React from 'react'
import { cn } from '@/lib/utils'

const badgeVariants = cva(
  'inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-medium leading-4 whitespace-nowrap',
  {
    variants: {
      variant: {
        default: 'border-slate-900/15 bg-slate-900/[0.03] text-slate-700',
        up: 'border-emerald-400/20 bg-emerald-400/10 text-emerald-700',
        down: 'border-rose-400/20 bg-rose-400/10 text-rose-700',
        demo: 'border-violet-400/30 bg-violet-500/15 text-violet-800',
        warn: 'border-amber-400/25 bg-amber-400/10 text-amber-800',
        info: 'border-cyan-400/25 bg-cyan-400/10 text-cyan-800',
      },
    },
    defaultVariants: { variant: 'default' },
  },
)

export interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement>, VariantProps<typeof badgeVariants> {}

export const Badge = React.forwardRef<HTMLSpanElement, BadgeProps>(({ className, variant, ...props }, ref) => (
  <span ref={ref} className={cn(badgeVariants({ variant }), className)} {...props} />
))
Badge.displayName = 'Badge'
