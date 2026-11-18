import type { ReactNode } from 'react'
import { Card, CardContent } from '@/components/ui/card'
import { cn } from '@/lib/utils'

/** A labelled key figure. Values truncate instead of overflowing on narrow screens. */
export function Stat({
  label,
  value,
  hint,
  tone,
  progress,
  className,
}: {
  label: string
  value: ReactNode
  hint?: ReactNode
  tone?: 'good' | 'warn' | 'bad'
  /** 0 to 100: a thin bar under the value, e.g. how full a store is. */
  progress?: number
  className?: string
}) {
  return (
    <Card className={cn('min-w-0 gap-1 py-4', className)}>
      <CardContent className="min-w-0 px-4">
        <p className="text-muted-foreground truncate text-xs font-medium tracking-wide uppercase">{label}</p>
        <p
          className={cn(
            'tabular mt-1 truncate text-2xl font-semibold tracking-tight',
            tone === 'good' && 'text-success',
            tone === 'warn' && 'text-warning',
            tone === 'bad' && 'text-destructive',
          )}
        >
          {value}
        </p>
        {progress !== undefined && (
          <div className="bg-muted mt-2 h-1 overflow-hidden rounded-full" role="progressbar" aria-valuenow={Math.round(progress)}>
            <div className="bg-primary h-full rounded-full" style={{ width: `${Math.min(100, Math.max(0, progress))}%` }} />
          </div>
        )}
        {hint && <p className="text-muted-foreground mt-0.5 truncate text-xs">{hint}</p>}
      </CardContent>
    </Card>
  )
}

export function PageHeader({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="mb-5 flex min-w-0 flex-wrap items-center justify-between gap-x-4 gap-y-2">
      <h1 className="min-w-0 truncate text-2xl font-semibold tracking-tight">{title}</h1>
      {children && <div className="flex min-w-0 items-center gap-2">{children}</div>}
    </div>
  )
}

export function Section({ title, children, aside }: { title: string; children: ReactNode; aside?: ReactNode }) {
  return (
    <section className="mt-6 min-w-0">
      <div className="mb-3 flex min-w-0 items-center justify-between gap-3">
        <h2 className="text-muted-foreground min-w-0 truncate text-sm font-semibold tracking-wide uppercase">
          {title}
        </h2>
        {aside}
      </div>
      {children}
    </section>
  )
}
