import { ArrowLeft, HeartPulse } from 'lucide-react'
import type { Mode, RangeCode } from '@/lib/types'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'

export const RANGES: { code: RangeCode; label: string }[] = [
  { code: '15m', label: '15 min' },
  { code: '1h', label: '1 hour' },
  { code: '6h', label: '6 hours' },
  { code: '24h', label: '24 hours' },
]

export function RangeSelector({
  value,
  onChange,
  className,
}: {
  value: RangeCode
  onChange: (r: RangeCode) => void
  className?: string
}) {
  return (
    <div
      role="radiogroup"
      aria-label="Time range"
      className={cn('inline-flex rounded-xl border border-slate-900/15 bg-slate-900/[0.03] p-0.5', className)}
    >
      {RANGES.map((r) => (
        <button
          key={r.code}
          type="button"
          role="radio"
          aria-checked={value === r.code}
          onClick={() => onChange(r.code)}
          className={cn(
            'rounded-[10px] px-3 py-1.5 text-xs font-medium transition-colors',
            value === r.code ? 'bg-slate-900/10 text-slate-900 shadow-sm' : 'text-slate-600 hover:text-slate-900',
          )}
        >
          {r.label}
        </button>
      ))}
    </div>
  )
}

interface Props {
  mode: Mode
  range: RangeCode
  onRange: (r: RangeCode) => void
  onReturnToLive: () => void
}

export function Toolbar({ mode, range, onRange, onReturnToLive }: Props) {
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-end">
      <div className="flex flex-wrap items-center gap-3">
        <RangeSelector value={range} onChange={onRange} />
        <Button variant="primary" asChild>
          <a href="#pipeline-health">
            <HeartPulse className="h-4 w-4" aria-hidden />
            Pipeline Health
          </a>
        </Button>
        {mode === 'demo' && (
          <Button variant="outline" onClick={onReturnToLive}>
            <ArrowLeft className="h-4 w-4" aria-hidden />
            Return to Live Data
          </Button>
        )}
      </div>
    </div>
  )
}
