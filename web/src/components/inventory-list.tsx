import { Search } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useInventory, useWatches, type InventoryItem, type InventoryQuery } from '@/api/hooks'
import { WatchToggle } from '@/components/watchlist'
import { Segmented } from '@/components/history-chart'
import { Section } from '@/components/stat'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { emptyInHours, formatBuckets } from '@/lib/ae2'
import { formatDuration, formatNumber, formatSi } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { usePreferences, type Locale } from '@/lib/preferences'
import { cn } from '@/lib/utils'

const PAGE = 100
type Kind = 'all' | NonNullable<InventoryQuery['kind']>

/**
 * Contents of an inventory (AE2 network) with net changes over the last hour and day.
 * Scanned every 10 s on the server; refreshed at the same pace.
 */
export function InventorySection({ resource, canEdit = false }: { resource: string; canEdit?: boolean }) {
  const t = useT()
  const { locale } = usePreferences()
  const [search, setSearch] = useState('')
  const [q, setQ] = useState('')
  const [sort, setSort] = useState<InventoryQuery['sort']>('amount')
  const [kind, setKind] = useState<Kind>('all')
  const [limit, setLimit] = useState(PAGE)

  useEffect(() => {
    const timer = setTimeout(() => {
      setQ(search.trim())
      setLimit(PAGE)
    }, 250)
    return () => clearTimeout(timer)
  }, [search])

  const inventory = useInventory(resource, { q, sort, kind: kind === 'all' ? undefined : kind, limit })
  const watched = new Set((useWatches(resource).data ?? []).map((w) => w.item))
  const data = inventory.data
  const scannedAt = data?.scannedAt ? new Date(data.scannedAt.wall).toLocaleTimeString(locale) : undefined

  return (
    <Section
      title={t('inv.title')}
      aside={
        scannedAt && (
          <span className="text-muted-foreground shrink-0 text-xs" title={t('inv.scanHint')}>
            {t('inv.scanned', { time: scannedAt })}
          </span>
        )
      }
    >
      <Card className="min-w-0 gap-0 py-0" data-testid="inventory">
        <div className="flex min-w-0 flex-wrap items-center gap-2 border-b p-3">
          <div className="relative min-w-0 flex-1 basis-48">
            <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2" />
            <Input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t('inv.search')}
              aria-label={t('inv.search')}
              className="pl-8"
            />
          </div>
          <Segmented<InventoryQuery['sort']>
            value={sort}
            onChange={(v) => {
              setSort(v)
              setLimit(PAGE)
            }}
            options={[
              { value: 'amount', label: t('inv.sortAmount') },
              { value: 'change', label: t('inv.sortChange') },
              { value: 'name', label: t('inv.sortName') },
            ]}
          />
          <Segmented<Kind>
            value={kind}
            onChange={(v) => {
              setKind(v)
              setLimit(PAGE)
            }}
            options={[
              { value: 'all', label: t('inv.all') },
              { value: 'item', label: t('inv.items') },
              { value: 'fluid', label: t('inv.fluids') },
            ]}
          />
        </div>

        {inventory.isPending ? (
          <div className="space-y-2 p-3">
            <Skeleton className="h-10" />
            <Skeleton className="h-10" />
            <Skeleton className="h-10" />
          </div>
        ) : !data || data.items.length === 0 ? (
          <p className="text-muted-foreground p-4 text-sm">
            {data && !data.scannedAt ? t('inv.notScanned') : q ? t('inv.noMatch') : t('inv.empty')}
          </p>
        ) : (
          <>
            <div className="text-muted-foreground hidden grid-cols-[minmax(0,1fr)_7rem_6rem_6rem] gap-3 border-b px-4 py-2 text-xs font-medium sm:grid">
              <span>{t('inv.name')}</span>
              <span className="text-right">{t('inv.amount')}</span>
              <span className="text-right">{t('inv.change1h')}</span>
              <span className="text-right">{t('inv.change24h')}</span>
            </div>
            <ul className="divide-border min-w-0 divide-y" data-testid="inventory-list">
              {data.items.map((item) => (
                <Row key={item.key} item={item} locale={locale} grid={resource} canEdit={canEdit} watched={watched.has(item.key)} />
              ))}
            </ul>
            <div className="text-muted-foreground flex min-w-0 flex-wrap items-center justify-between gap-2 border-t px-4 py-2 text-xs">
              <span>{t('inv.showing', { shown: formatNumber(data.items.length, locale), total: formatNumber(data.total, locale) })}</span>
              {data.items.length < data.total && (
                <Button variant="ghost" size="sm" onClick={() => setLimit((l) => Math.min(l + PAGE, 500))} disabled={limit >= 500}>
                  {t('inv.more')}
                </Button>
              )}
            </div>
          </>
        )}
      </Card>
    </Section>
  )
}

function Row({
  item,
  locale,
  grid,
  canEdit,
  watched,
}: {
  item: InventoryItem
  locale: Locale
  grid: string
  canEdit: boolean
  watched: boolean
}) {
  const t = useT()
  const emptyIn = emptyInHours(item.amount, item.change1h, item.change24h)
  const hint = emptyIn === undefined ? '' : t('watch.emptyIn', { time: formatDuration(emptyIn * 3_600_000, locale) })
  const amount = item.kind === 'fluid' ? `${formatBuckets(item.amount, locale)} ${t('ae2.buckets')}` : formatSi(item.amount, locale)
  const exact = item.kind === 'fluid' ? `${formatNumber(item.amount, locale)} mB` : formatNumber(item.amount, locale)
  return (
    <li className="grid min-w-0 grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 px-4 py-2 sm:grid-cols-[minmax(0,1fr)_7rem_6rem_6rem]">
      <div className="flex min-w-0 items-center gap-1">
        {canEdit && <WatchToggle grid={grid} item={item.key} watched={watched} name={item.name} />}
        <div className="min-w-0">
          <p className={cn('truncate text-sm font-medium', item.amount === 0 && 'text-muted-foreground')}>{item.name}</p>
          <p className="text-muted-foreground truncate text-xs">
            {hint ? <span className="text-destructive">{hint}</span> : item.key}
          </p>
        </div>
      </div>
      <span className="tabular text-right text-sm font-semibold" title={exact}>
        {amount}
      </span>
      <div className="text-muted-foreground col-span-2 flex justify-end gap-3 text-xs sm:contents">
        <Change value={item.change1h} kind={item.kind} label={t('inv.change1h')} locale={locale} />
        <Change value={item.change24h} kind={item.kind} label={t('inv.change24h')} locale={locale} />
      </div>
    </li>
  )
}

function Change({ value, kind, label, locale }: { value: number; kind: string; label: string; locale: Locale }) {
  const text = value === 0 ? '–' : `${value > 0 ? '+' : '−'}${kind === 'fluid' ? formatBuckets(Math.abs(value), locale) : formatSi(Math.abs(value), locale)}`
  return (
    <span
      className={cn('tabular text-right text-xs sm:text-sm', value > 0 && 'text-success', value < 0 && 'text-destructive')}
      title={`${label}: ${formatNumber(value, locale)}`}
    >
      <span className="sm:hidden">{label} </span>
      {text}
    </span>
  )
}
