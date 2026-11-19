import { Check, X } from 'lucide-react'
import type { ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { useT } from '@/lib/i18n'
import { cn } from '@/lib/utils'

/** Building blocks of the world action forms (ADR-0016): Flux and AE2 editors share them. */

/** A labelled form field; `hint` sits on the right, e.g. a character counter. */
export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: ReactNode }) {
  return (
    <label className="block min-w-0 space-y-1">
      <span className="text-muted-foreground flex min-w-0 items-baseline justify-between gap-2 text-xs font-medium">
        <span className="truncate">{label}</span>
        {hint && <span className="shrink-0 font-normal">{hint}</span>}
      </span>
      {children}
    </label>
  )
}

export function Counter({ value, max }: { value: string; max: number }) {
  return <span className={cn('tabular', value.length > max && 'text-destructive')}>{value.length}/{max}</span>
}

export function Feedback({ error, message }: { error?: string; message?: string }) {
  if (error) return <p className="text-destructive text-sm" role="alert">{error}</p>
  if (message) return <p className="text-muted-foreground text-sm" role="status">{message}</p>
  return null
}

export function Actions({ pending, onCancel, disabled }: { pending: boolean; onCancel: () => void; disabled?: boolean }) {
  const t = useT()
  return (
    <div className="flex flex-wrap gap-2">
      <Button type="submit" size="sm" disabled={pending || disabled}>
        <Check /> {t('common.save')}
      </Button>
      <Button type="button" size="sm" variant="ghost" onClick={onCancel}>
        <X /> {t('common.cancel')}
      </Button>
    </div>
  )
}
