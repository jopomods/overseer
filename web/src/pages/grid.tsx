import { Link, useNavigate, useParams } from '@tanstack/react-router'
import { ArrowLeft, Check, Pencil, UserPlus, X } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import {
  ApiError,
  useAccessRequests,
  useAllowedActions,
  useDetails,
  useAddShare,
  useDecideAccess,
  useOverrides,
  useRegistration,
  useRemoveShare,
  useSetOverride,
  useShares,
  useSnapshot,
  type Snapshot,
} from '@/api/hooks'
import { ActionLog } from '@/components/flux-edit'
import { GridStateBadge } from '@/components/grid-state-badge'
import { McText } from '@/components/mc-text'
import { CraftProvider } from '@/components/craft'
import { CraftingSection } from '@/components/crafting-cpus'
import { FlowSection } from '@/components/ae2-flow'
import { BusEditSlot } from '@/components/bus-edit'
import { BusesSection } from '@/components/bus-list'
import { DrivesSection } from '@/components/drives-section'
import { OfflineDevicesSection } from '@/components/offline-devices'
import { PatternProvidersSection } from '@/components/pattern-providers'
import { HistoryChart } from '@/components/history-chart'
import { InventorySection } from '@/components/inventory-list'
import { WatchlistSection } from '@/components/watchlist'
import { LiveBadge } from '@/components/live-badge'
import { useSession } from '@/components/session'
import { Section, Stat } from '@/components/stat'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { AE2_ACTIONS, buses, cpus, drives, offlineOf, patternProviders, patternsOf, providerLabel, restocksOf, formatAe, formatAeRate, formatBuckets, formatPos, grids, idPos, shortDimension, type Ae2Grid } from '@/lib/ae2'
import { formatBytes, formatDuration, formatNumber, formatSi } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { plainText } from '@/lib/mc-text'
import { usePreferences, type Locale } from '@/lib/preferences'
import { tiersOf, type TierScheme } from '@/lib/tiers'

/** One AE2 network: power, inventory totals, crafting CPUs, history. */
export function GridPage() {
  const t = useT()
  const { locale } = usePreferences()
  const { live } = useSession()
  const { gridId } = useParams({ from: '/app/ae2/$gridId' })
  const snapshot = useSnapshot('ae2', { pollMs: live === 'live' ? false : 5000 })
  const overrides = useOverrides('ae2').data
  const grid = grids(snapshot.data, overrides).find((g) => g.id === gridId && g.kind !== 'hidden')
  const [renaming, setRenaming] = useState(false)
  const allowed = useAllowedActions(grid?.resource.ref.key ?? '', grid !== undefined).data ?? NONE
  const server = useSnapshot('server').data
  const scheme = server?.resources.find((r) => r.ref.key === 'server/server/main')?.attributes.energyTiers as TierScheme | undefined
  const tiers = useMemo(() => tiersOf(scheme), [scheme])
  const patternRows = useDetails(grid?.resource.ref.key ?? '', grid !== undefined).data
  const patterns = useMemo(() => patternsOf(patternRows), [patternRows])
  const offline = useMemo(() => offlineOf(patternRows), [patternRows])
  const restocks = useMemo(() => restocksOf(patternRows), [patternRows])

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
              <McText text={grid.name ?? t('ae2.unnamed', { pos: formatPos(grid.pos) })} />
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
          hint={`${formatNumber(fill * 100, locale, 0)} % · ${formatAe(grid.powerMax, locale)}${powerEta(grid, t, locale)}`}
          progress={fill * 100}
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

      <OfflineDevicesSection devices={offline} />

      <CraftProvider grid={key} enabled={allowed.has(AE2_ACTIONS.craftPlan)} patterns={patterns}>
        <CraftingSection cpus={cpus(snapshot.data, grid.id)} grid={key} allowed={allowed} />

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
            <HistoryChart
              resource={key}
              series={[{ metric: 'bytes_used', label: t('drive.chart'), color: 'var(--chart-4)' }]}
              format={(v) => formatBytes(v, locale)}
            />
          </div>
        </Section>

        <DrivesSection drives={drives(snapshot.data, grid.id)} grid={key} editable={allowed.has(AE2_ACTIONS.driveEdit)} />

        <PatternProvidersSection
          grid={key}
          providers={patternProviders(snapshot.data, grid.id)}
          editable={allowed.has(AE2_ACTIONS.providerEdit)}
          tiers={tiers}
        />

        <FlowSection grid={key} uptime={snapshot.data?.at.uptimeMillis} buses={buses(snapshot.data, grid.id)} />

        <BusesSection
          buses={buses(snapshot.data, grid.id)}
          extra={allowed.has(AE2_ACTIONS.busEdit) ? (b) => <BusEditSlot grid={key} bus={b} /> : undefined}
        />

        <WatchlistSection grid={grid.resource} canEdit={grid.canEdit} canRestock={allowed.has(AE2_ACTIONS.restock)} restocks={restocks} />

        <InventorySection resource={key} canEdit={grid.canEdit} />
      </CraftProvider>

      {grid.canEdit && <AccessSection resource={key} />}
      {grid.canEdit && grid.kind === 'registered' && <UnregisterButton resource={key} />}

      <Section title={t('edit.log')}>
        <ActionLog resource={key} describe={(ref) => describePart(snapshot.data, grid.id, ref, t)} />
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

const NONE: Set<string> = new Set()

/** "CPU #3", "Drive · 1, 2, 3": the part a logged change was about. */
function describePart(snapshot: Snapshot | undefined, gridId: string, ref: string, t: ReturnType<typeof useT>) {
  const cpu = cpus(snapshot, gridId).find((c) => c.resource.ref.key === ref)
  if (cpu) return plainText(cpu.name ?? t('cpu.unnamed', { n: cpu.number }))
  const drive = drives(snapshot, gridId).find((d) => d.resource.ref.key === ref)
  if (drive) return `${t(drive.block === 'ae2:chest' ? 'drive.chest' : 'drive.drive')} · ${idPos(drive.id)}`
  const provider = patternProviders(snapshot, gridId).find((p) => p.resource.ref.key === ref)
  if (provider) return plainText(providerLabel(provider)) || t('provider.unnamed')
  return undefined
}

/** Shares of this grid; owners and admins only (ADR-0011). */
function AccessSection({ resource }: { resource: string }) {
  const t = useT()
  const shares = useShares(resource, true)
  const add = useAddShare(resource)
  const remove = useRemoveShare(resource)
  const requests = (useAccessRequests().data ?? []).filter((r) => r.resource === resource)
  const decide = useDecideAccess()
  const [name, setName] = useState('')
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!name.trim()) return
    add.mutate(name.trim(), { onSuccess: () => setName('') })
  }
  const error =
    add.error instanceof ApiError && add.error.status === 400 ? t('ae2.shareUnknown') : add.error ? t('ae2.shareFailed') : null
  return (
    <Section title={t('ae2.access')}>
      <Card className="min-w-0 gap-0 py-1" data-testid="grid-access">
        <p className="text-muted-foreground px-4 py-2.5 text-sm">{t('ae2.accessHint')}</p>
        {requests.length > 0 && (
          <ul className="border-border bg-primary/5 divide-border divide-y border-t" data-testid="access-requests">
            {requests.map((r) => (
              <li key={r.player} className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-2 px-4 py-2">
                <span className="min-w-0 flex-1 basis-32 truncate text-sm">
                  <span className="font-medium">{r.name ?? r.player}</span>
                  <span className="text-muted-foreground"> · {t('ae2.asks')}</span>
                </span>
                <div className="flex shrink-0 gap-1">
                  <Button size="sm" disabled={decide.isPending} onClick={() => decide.mutate({ resource, player: r.player, approve: true })}>
                    <Check /> {t('ae2.approve')}
                  </Button>
                  <Button size="sm" variant="outline" disabled={decide.isPending} onClick={() => decide.mutate({ resource, player: r.player, approve: false })}>
                    {t('ae2.deny')}
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
        <ul className="divide-border border-border divide-y border-t" data-testid="share-list">
          {shares.data?.length === 0 && <li className="text-muted-foreground px-4 py-2.5 text-sm">{t('ae2.noShares')}</li>}
          {shares.data?.map((s) => (
            <li key={s.player} className="flex min-w-0 items-center gap-3 px-4 py-2">
              <span className="bg-primary/15 text-primary flex size-8 shrink-0 items-center justify-center rounded text-sm font-bold">
                {(s.name ?? '?').slice(0, 1).toUpperCase()}
              </span>
              <span className="min-w-0 flex-1 truncate text-sm font-medium">{s.name ?? s.player}</span>
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={t('ae2.unshare')}
                disabled={remove.isPending}
                onClick={() => remove.mutate(s.player)}
              >
                <X />
              </Button>
            </li>
          ))}
        </ul>
        <form onSubmit={submit} className="border-border flex min-w-0 flex-wrap gap-2 border-t px-4 py-2.5">
          <Input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('ae2.sharePlaceholder')}
            maxLength={16}
            className="min-w-0 flex-1 basis-40"
            aria-label={t('ae2.sharePlaceholder')}
          />
          <Button type="submit" size="sm" className="shrink-0" disabled={add.isPending || !name.trim()}>
            <UserPlus /> {t('ae2.share')}
          </Button>
          {error && <p className="text-destructive w-full text-xs">{error}</p>}
        </form>
      </Card>
    </Section>
  )
}

/** Registered grids (no controller) can be turned back into hidden ones. */
function UnregisterButton({ resource }: { resource: string }) {
  const t = useT()
  const navigate = useNavigate()
  const { remove } = useRegistration('ae2')
  return (
    <div className="mt-3 flex min-w-0 flex-wrap items-center justify-between gap-2">
      <p className="text-muted-foreground min-w-0 text-xs">{t('ae2.registered')}</p>
      <Button
        size="sm"
        variant="outline"
        disabled={remove.isPending}
        onClick={() => remove.mutate(resource, { onSuccess: () => navigate({ to: '/ae2' }) })}
      >
        {t('ae2.unregister')}
      </Button>
    </div>
  )
}

/** " · full in ~X" / " · empty in ~X" from AE2's average injection and usage (AE per tick). */
function powerEta(grid: Ae2Grid, t: ReturnType<typeof useT>, locale: Locale): string {
  const perTick = grid.injection - grid.usage
  if (Math.abs(perTick) < 0.01) return ''
  if (perTick > 0) {
    if (grid.powerStored >= grid.powerMax * 0.999) return ''
    return ` · ${t('energy.fullIn', { time: formatDuration(((grid.powerMax - grid.powerStored) / perTick) * 50, locale) })}`
  }
  return ` · ${t('energy.emptyIn', { time: formatDuration((grid.powerStored / -perTick) * 50, locale) })}`
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
