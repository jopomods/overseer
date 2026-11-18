import { Link, useParams } from '@tanstack/react-router'
import { ArrowLeft, Check, Pencil, X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useOverrides, useSetOverride, useSnapshot } from '@/api/hooks'
import { GridStateBadge } from '@/components/grid-state-badge'
import { HistoryChart } from '@/components/history-chart'
import { LiveBadge } from '@/components/live-badge'
import { useSession } from '@/components/session'
import { Section, Stat } from '@/components/stat'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { formatAe, formatAeRate, formatBuckets, formatPos, grids, shortDimension, type Ae2Grid } from '@/lib/ae2'
import { formatBytes, formatNumber, formatSi } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { usePreferences } from '@/lib/preferences'

/** One AE2 network: power, inventory totals, crafting CPUs, history. */
export function GridPage() {
  const t = useT()
  const { locale } = usePreferences()
  const { live } = useSession()
  const { gridId } = useParams({ from: '/app/ae2/$gridId' })
  const snapshot = useSnapshot('ae2', { pollMs: live === 'live' ? false : 5000 })
  const overrides = useOverrides('ae2').data
  const grid = grids(snapshot.data, overrides).find((g) => g.id === gridId)
  const [renaming, setRenaming] = useState(false)

  if (snapshot.isPending) return <Skeleton className="h-64" />
  if (!grid) {
    return (
      <>
        <BackLink />
        <p className="text-muted-foreground mt-4 text-sm">{t('ae2.notFound')}</p>
      </>
    )
  }

  const fill = grid.powerMax > 0 ? grid.powerStored / grid.powerMax : 0
  const key = grid.resource.ref.key
  return (
    <>
      <BackLink />
      <div className="mt-3 mb-5 flex min-w-0 flex-wrap items-center justify-between gap-x-4 gap-y-2">
        {renaming ? (
          <RenameForm grid={grid} onDone={() => setRenaming(false)} />
        ) : (
          <div className="flex min-w-0 items-center gap-3">
            <h1 className="min-w-0 truncate text-2xl font-semibold tracking-tight">
              {grid.name ?? t('ae2.unnamed', { pos: formatPos(grid.pos) })}
            </h1>
            {grid.canEdit && (
              <Button variant="ghost" size="icon-sm" aria-label={t('ae2.rename')} onClick={() => setRenaming(true)}>
                <Pencil />
              </Button>
            )}
            <GridStateBadge state={grid.state} />
          </div>
        )}
        <LiveBadge state={live} dataAt={snapshot.data?.at.wall} />
      </div>

      <div className="grid min-w-0 grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat
          label={t('ae2.stored')}
          value={formatAe(grid.powerStored, locale)}
          hint={`${formatNumber(fill * 100, locale, 0)} % · ${formatAe(grid.powerMax, locale)}`}
        />
        <Stat
          label={t('ae2.usage')}
          value={formatAeRate(grid.usage, locale)}
          hint={`${t('ae2.injection')}: ${formatAeRate(grid.injection, locale)}`}
        />
        <Stat
          label={t('ae2.items')}
          value={formatSi(grid.items, locale)}
          hint={t('ae2.types', { count: formatNumber(grid.itemTypes, locale) })}
        />
        <Stat
          label={t('ae2.cpus')}
          value={`${grid.cpusBusy}/${grid.cpus}`}
          hint={t('ae2.cpusBusy', { busy: grid.cpusBusy, count: grid.cpus })}
        />
      </div>

      <Section title={t('chart.history')}>
        <div className="grid min-w-0 gap-3 xl:grid-cols-2">
          <HistoryChart
            testId="chart-ae2-power"
            resource={key}
            series={[
              { metric: 'power_usage', label: t('ae2.usage'), color: 'var(--primary)' },
              { metric: 'power_injection', label: t('ae2.injection'), color: 'var(--success)' },
            ]}
            format={(v) => formatAeRate(v, locale)}
          />
          <HistoryChart
            resource={key}
            series={[{ metric: 'power_stored', label: t('ae2.stored'), color: 'var(--chart-5)' }]}
            format={(v) => formatAe(v, locale)}
          />
          <HistoryChart
            resource={key}
            series={[{ metric: 'items', label: t('ae2.items'), color: 'var(--chart-2)' }]}
            format={(v) => formatSi(v, locale)}
          />
          <HistoryChart
            resource={key}
            series={[{ metric: 'item_types', label: t('ae2.itemTypes'), color: 'var(--chart-3)' }]}
            format={(v) => formatNumber(v, locale)}
          />
        </div>
      </Section>

      <Section title={t('ae2.details')}>
        <Card className="min-w-0 py-1">
          <dl className="divide-border grid min-w-0 divide-y sm:grid-cols-2 sm:divide-y-0" data-testid="grid-details">
            <Detail label={t('ae2.owner')} value={grid.ownerName || t('common.unknown')} />
            <Detail label={t('ae2.location')} value={`${shortDimension(grid.dimension)} · ${formatPos(grid.pos)}`} />
            <Detail label={t('ae2.fluids')} value={`${formatBuckets(grid.fluids, locale)} ${t('ae2.buckets')} · ${t('ae2.types', { count: formatNumber(grid.fluidTypes, locale) })}`} />
            <Detail label={t('ae2.idle')} value={formatAeRate(grid.idle, locale)} />
            <Detail label={t('ae2.channels')} value={formatNumber(grid.channels, locale)} />
            <Detail label={t('ae2.nodes')} value={formatNumber(grid.nodes, locale)} />
            <Detail label={t('ae2.controllers')} value={formatNumber(grid.controllers, locale)} />
            <Detail
              label={t('ae2.cpuStorage')}
              value={`${formatBytes(grid.cpuStorage, locale)} · ${t('ae2.coProcessors')}: ${formatNumber(grid.coProcessors, locale)}`}
            />
          </dl>
        </Card>
      </Section>
    </>
  )
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex min-w-0 items-baseline justify-between gap-3 px-4 py-2.5">
      <dt className="text-muted-foreground shrink-0 text-sm">{label}</dt>
      <dd className="tabular min-w-0 truncate text-right text-sm font-medium">{value}</dd>
    </div>
  )
}

function RenameForm({ grid, onDone }: { grid: Ae2Grid; onDone: () => void }) {
  const t = useT()
  const save = useSetOverride('ae2')
  const [value, setValue] = useState(grid.name ?? '')
  const submit = (event: FormEvent) => {
    event.preventDefault()
    save.mutate({ resource: grid.resource.ref.key, key: 'name', value: value.trim() || null }, { onSuccess: onDone })
  }
  return (
    <form onSubmit={submit} className="flex min-w-0 flex-1 basis-72 flex-wrap gap-2" data-testid="rename-form">
      <Input
        autoFocus
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder={t('ae2.namePlaceholder')}
        maxLength={48}
        className="min-w-0 flex-1 basis-48"
        aria-label={t('ae2.name')}
      />
      <div className="flex shrink-0 gap-1">
        <Button type="submit" size="sm" disabled={save.isPending}>
          <Check /> {t('common.save')}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone} aria-label={t('common.cancel')}>
          <X />
        </Button>
      </div>
    </form>
  )
}

function BackLink() {
  const t = useT()
  return (
    <Link to="/ae2" className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1.5 text-sm">
      <ArrowLeft className="size-4" /> {t('ae2.back')}
    </Link>
  )
}
