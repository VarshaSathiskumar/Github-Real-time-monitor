import type { ReactNode } from 'react'
import { Badge } from '@/components/ui/badge'
import { CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import type { Mode } from '@/lib/types'

interface Props {
  title: string
  description?: ReactNode
  mode: Mode
  actions?: ReactNode
  windowLabel?: string
}

export function SectionHeader({ title, description, mode, actions, windowLabel }: Props) {
  return (
    <CardHeader>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <CardTitle>{title}</CardTitle>
            {mode === 'demo' && <Badge variant="demo">Demo Data</Badge>}
            {windowLabel && <Badge>{windowLabel}</Badge>}
          </div>
          {description && <CardDescription className="mt-1 max-w-2xl">{description}</CardDescription>}
        </div>
        {actions}
      </div>
    </CardHeader>
  )
}
