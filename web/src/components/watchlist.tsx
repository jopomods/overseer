import { ChevronDown, Star, X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useRemoveWatch, useSetWatch, useWatches, type Resource, type Watch } from '@/api/hooks'
import { HistoryChart } from '@/components/history-chart'
import { Section } from '@/components/stat'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Progress } from '@/components/ui/progress'
import { forecast, formatBuckets, type Forecast } from '@/lib/ae2'
import { formatDuration, formatSi } from '@/lib/format'
import { useT, type Translate } from '@/lib/i18n'
import { usePreferences, type Locale } from '@/lib/preferences'
import { metric } from '@/lib/snapshot'
import { cn } from '@/lib/utils'

/** A forecast further out than this is no forecast. */
const HORIZON_HOURS = 24 * 14

/**
 * Watched items of an ME network: live amount (every second), target, deficit and a forecast
 * from the net changes. Owners set targets; everyone who sees the network sees the list.
 */
export function WatchlistSection({ grid, canEdit }: { grid: Resource; canEdit: boolean }) {
  const t = useT()
  const key = grid.ref.key
  const watches = useWatches(key)
  const list = watches.data ?? []
  if (watches.isPending || (list.length === 0 && !canEdit)) return null
  const below = list.filter((w) => w.target != null && amountOf(grid, w) < w.target).length

  return (
    <Section
      title={t('watch.title')}
      aside={
        list.length > 0 && (
          <span className={cn('shrink-0 text-xs', below > 0 ? 'text-destructive font-medium' : 'text-muted-foreground')}>
            {below > 0 ? t('watch.below', { count: below }) : t('watch.allGood')}
          </span>
        )
      }
    >
      {list.length === 0 ? (
        <Card className="text-muted-foreground min-w-0 px-4 py-4 text-sm">{t('watch.empty')}</Card>
      ) : (
        <ul className="grid min-w-0 items-start gap-3 md:grid-cols-2" data-testid="watchlist">
          {[...list]
            .sort((a, b) => urgency(grid, b) - urgency(grid, a) || a.name.localeCompare(b.name))
            .map((w) => (
              <WatchCard key={w.item} grid={grid} watch={w} canEdit={canEdit} />
            ))}
        </ul>
      )}
    </Section>
  )
}

function amountOf(grid: Resource, w: Watch) {
  return metric(grid, `stock:${w.item}`) ?? 0
}

/** Deficits first (largest share missing), then items that run out soonest. */
function urgency(grid: Resource, w: Watch) {
  const amount = amountOf(grid, w)
  const f = forecast(amount, w.change1h, w.change24h, w.target)
  if (w.target && f.deficit > 0) return 2 + f.deficit / w.target
  if (f.emptyIn !== undefined) return 1 / (1 + f.emptyIn)
  return 0
}

function WatchCard({ grid, watch, canEdit }: { grid: Resource; watch: Watch; canEdit: boolean }) {
  const t = useT()
  const { locale } = usePreferences()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState(false)
  const remove = useRemoveWatch(grid.ref.key)
  const live = metric(grid, `stock:${watch.item}`)
  const amount = live ?? 0
  const f = forecast(amount, watch.change1h, watch.change24h, watch.target)
  const fmt = (v: number) => format(v, watch.kind, locale)

  return (
    <li className="min-w-0">
      <Card className={cn('min-w-0 gap-0 py-0', f.deficit > 0 && 'border-destructive/50')}>
        <div className="flex min-w-0 flex-col gap-2 px-4 py-3">
          <div className="flex min-w-0 items-center gap-2">
            <Star className="text-warning size-4 shrink-0 fill-current" />
            <span className="min-w-0 flex-1 truncate text-sm font-medium" title={watch.item}>
              {watch.name}
            </span>
            {canEdit && (
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={t('watch.remove', { name: watch.name })}
                onClick={() => remove.mutate(watch.item)}
                disabled={remove.isPending}
              >
                <X />
              </Button>
            )}
          </div>
          <div className="flex min-w-0 items-baseline justify-between gap-2">
            <span className="tabular text-xl font-semibold">{live === undefined ? '–' : fmt(amount)}</span>
            {editing ? (
              <TargetForm grid={grid.ref.key} watch={watch} onDone={() => setEditing(false)} />
            ) : (
              <button
                type="button"
                className={cn('text-muted-foreground tabular shrink-0 text-xs', canEdit && 'hover:text-foreground underline-offset-2 hover:underline')}
                onClick={() => canEdit && setEditing(true)}
                disabled={!canEdit}
              >
                {watch.target != null ? t('watch.target', { target: fmt(watch.target) }) : canEdit ? t('watch.setTarget') : ''}
              </button>
            )}
          </div>
          {watch.target != null && watch.target > 0 && (
            <Progress
              value={Math.min(100, (amount / watch.target) * 100)}
              className={cn(f.deficit > 0 && '[&>[data-slot=progress-indicator]]:bg-destructive')}
              aria-label={t('watch.progress')}
            />
          )}
          <div className="flex min-w-0 flex-wrap items-center justify-between gap-x-3 gap-y-1 text-xs">
            <span className={cn('tabular', f.deficit > 0 ? 'text-destructive font-medium' : 'text-muted-foreground')}>
              {f.deficit > 0 ? t('watch.missing', { amount: fmt(f.deficit) }) : rate(f, watch.kind, locale, t)}
            </span>
            <span className="text-muted-foreground tabular">{forecastText(f, t, locale)}</span>
          </div>
          <button
            type="button"
            className="text-muted-foreground hover:text-foreground flex items-center gap-1 self-start text-xs"
            aria-expanded={open}
            onClick={() => setOpen((o) => !o)}
          >
            <ChevronDown className={cn('size-3.5 transition-transform', open && 'rotate-180')} />
            {t('watch.history')}
          </button>
        </div>
        {open && (
          <div className="min-w-0 border-t px-1 pb-1">
            <HistoryChart
              resource={grid.ref.key}
              series={[{ metric: `stock:${watch.item}`, label: watch.name, color: 'var(--chart-2)' }]}
              format={fmt}
            />
          </div>
        )}
      </Card>
    </li>
  )
}

function TargetForm({ grid, watch, onDone }: { grid: string; watch: Watch; onDone: () => void }) {
  const t = useT()
  const set = useSetWatch(grid)
  const [value, setValue] = useState(watch.target != null ? String(watch.target) : '')
  const submit = (e: FormEvent) => {
    e.preventDefault()
    const trimmed = value.trim()
    const target = trimmed === '' ? null : Math.max(0, Math.round(Number(trimmed)))
    if (target !== null && !Number.isFinite(target)) return
    set.mutate({ item: watch.item, target }, { onSuccess: onDone })
  }
  return (
    <form className="flex shrink-0 items-center gap-1" onSubmit={submit} data-testid="target-form">
      <Input
        autoFocus
        inputMode="numeric"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        aria-label={t('watch.targetLabel')}
        placeholder={watch.kind === 'fluid' ? 'mB' : '0'}
        className="h-7 w-28 text-xs"
      />
      <Button type="submit" size="sm" className="h-7 px-2 text-xs" disabled={set.isPending}>
        {t('common.save')}
      </Button>
    </form>
  )
}

function format(v: number, kind: string, locale: Locale) {
  return kind === 'fluid' ? `${formatBuckets(v, locale)} B` : formatSi(v, locale)
}

function rate(f: Forecast, kind: string, locale: Locale, t: Translate) {
  if (f.perHour === 0) return t('watch.steady')
  const sign = f.perHour > 0 ? '+' : '−'
  return t('watch.perHour', { rate: `${sign}${format(Math.abs(f.perHour), kind, locale)}` })
}

function forecastText(f: Forecast, t: Translate, locale: Locale) {
  if (f.targetIn !== undefined && f.targetIn < HORIZON_HOURS) {
    return t('watch.targetIn', { time: formatDuration(f.targetIn * 3_600_000, locale) })
  }
  if (f.emptyIn !== undefined && f.emptyIn < HORIZON_HOURS) {
    return t('watch.emptyIn', { time: formatDuration(f.emptyIn * 3_600_000, locale) })
  }
  return ''
}

/** Star toggle for the contents list. */
export function WatchToggle({ grid, item, watched, name }: { grid: string; item: string; watched: boolean; name: string }) {
  const t = useT()
  const set = useSetWatch(grid)
  const remove = useRemoveWatch(grid)
  const busy = set.isPending || remove.isPending
  return (
    <Button
      variant="ghost"
      size="icon-sm"
      aria-pressed={watched}
      aria-label={watched ? t('watch.remove', { name }) : t('watch.add', { name })}
      disabled={busy}
      onClick={() => (watched ? remove.mutate(item) : set.mutate({ item }))}
    >
      <Star className={cn(watched ? 'text-warning fill-current' : 'text-muted-foreground')} />
    </Button>
  )
}
