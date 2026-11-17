import { Link, useParams } from '@tanstack/react-router'
import { ArrowLeft, BatteryCharging, Cable, Check, Cpu, Pencil, Plug, Search, X, type LucideIcon } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { useHistory, useOverrides, useSessions, useSetOverride, useSnapshot } from '@/api/hooks'
import { Button } from '@/components/ui/button'
import { EnergyFlow } from '@/components/energy-flow'
import { HistoryChart } from '@/components/history-chart'
import { ZoneChart } from '@/components/zone-chart'
import { LiveBadge } from '@/components/live-badge'
import { useSession } from '@/components/session'
import { Section, Stat } from '@/components/stat'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { devices, members, networks, zones, type DeviceType, type FluxDevice } from '@/lib/flux'
import { forecastEmpty } from '@/lib/forecast'
import { formatDuration, formatEnergy, formatRate } from '@/lib/format'
import { useT, type MessageKey } from '@/lib/i18n'
import { usePreferences } from '@/lib/preferences'
import { cn } from '@/lib/utils'
import { SecurityBadge } from '@/components/security-badge'

const TYPE_ICON: Record<DeviceType, LucideIcon> = {
  plug: Plug,
  point: Cable,
  storage: BatteryCharging,
  controller: Cpu,
}
const TYPES: DeviceType[] = ['plug', 'point', 'storage', 'controller']

/** One Flux network: live totals, zones, devices and members. */
export function NetworkPage() {
  const t = useT()
  const { locale, energyUnit } = usePreferences()
  const { live } = useSession()
  const { networkId } = useParams({ from: '/app/energy/$networkId' })
  const snapshot = useSnapshot('flux', { pollMs: live === 'live' ? false : 5000 })
  const network = networks(snapshot.data).find((n) => n.id === networkId)
  const overrides = useOverrides('flux').data
  const deviceList = useMemo(() => devices(snapshot.data, networkId, overrides), [snapshot.data, networkId, overrides])
  const memberList = members(snapshot.data, networkId)

  if (snapshot.isPending) return <Skeleton className="h-64" />

  if (!network) {
    return (
      <>
        <BackLink />
        <p className="text-muted-foreground mt-4 text-sm">{t('energy.notFound')}</p>
      </>
    )
  }

  const balance = network.input - network.output
  return (
    <>
      <BackLink />
      <div className="mt-3 mb-5 flex min-w-0 flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <div className="flex min-w-0 items-center gap-3">
          <span className="size-4 shrink-0 rounded-full" style={{ backgroundColor: network.color }} />
          <h1 className="min-w-0 truncate text-2xl font-semibold tracking-tight">{network.name}</h1>
          <SecurityBadge security={network.security} />
        </div>
        <LiveBadge state={live} dataAt={snapshot.data?.at.wall} />
      </div>

      <div className="grid min-w-0 grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label={t('energy.input')} value={formatRate(network.input, energyUnit, locale)} />
        <Stat label={t('energy.output')} value={formatRate(network.output, energyUnit, locale)} />
        <Stat
          label={t('energy.net')}
          value={`${balance > 0 ? '+' : ''}${formatRate(balance, energyUnit, locale)}`}
          tone={balance > 0 ? 'good' : balance < 0 ? 'warn' : undefined}
        />
        <StoredStat resource={network.resource.ref.key} energy={network.energy} />
      </div>

      <Section title={t('chart.history')}>
        <div className="grid min-w-0 gap-3 xl:grid-cols-2">
          <HistoryChart
            testId="chart-flow"
            resource={network.resource.ref.key}
            series={[
              { metric: 'energy_input', label: t('energy.input'), color: 'var(--success)' },
              { metric: 'energy_output', label: t('energy.output'), color: 'var(--primary)' },
            ]}
            format={(v) => formatRate(v, energyUnit, locale)}
          />
          <HistoryChart
            resource={network.resource.ref.key}
            series={[{ metric: 'energy', label: t('chart.storedEnergy'), color: 'var(--chart-5)' }]}
            format={(v) => formatEnergy(v, energyUnit, locale)}
          />
        </div>
      </Section>

      <Section title={t('energy.flow')}>
        <EnergyFlow devices={deviceList} />
      </Section>

      <ZonesSection devices={deviceList} />

      <Section title={t('energy.zoneHistory')}>
        <ZoneChart devices={deviceList} />
      </Section>
      <DevicesSection devices={deviceList} />

      <Section title={t('energy.members')}>
        <Card className="min-w-0 py-1">
          <ul className="divide-border divide-y" data-testid="member-list">
            {memberList.map((m) => (
              <li key={m.uuid} className="flex min-w-0 items-center gap-3 px-4 py-2.5">
                <span className="bg-primary/15 text-primary flex size-8 shrink-0 items-center justify-center rounded text-sm font-bold">
                  {m.name.slice(0, 1).toUpperCase()}
                </span>
                <span className="min-w-0 flex-1 truncate text-sm font-medium">{m.name}</span>
                <Badge variant={m.access === 'blocked' ? 'destructive' : 'secondary'}>
                  {t(`energy.access.${m.access}` as MessageKey)}
                </Badge>
              </li>
            ))}
          </ul>
        </Card>
      </Section>
    </>
  )
}

/** Stored energy with a forecast from the last hour of history. */
function StoredStat({ resource, energy }: { resource: string; energy: number }) {
  const t = useT()
  const { locale, energyUnit } = usePreferences()
  const uptime = useSessions().data?.uptimeMillis
  const history = useHistory(resource, ['energy'], 3_600_000, uptime)
  const points = history.data?.series[0]?.points.map((p) => ({ t: p.t, value: p.avg })) ?? []
  const forecast = forecastEmpty(points)
  const hint =
    forecast.kind === 'emptying'
      ? t('energy.emptyIn', { time: formatDuration(forecast.msUntilEmpty, locale) })
      : forecast.kind === 'charging'
        ? t('energy.charging')
        : forecast.kind === 'stable'
          ? t('energy.stable')
          : undefined
  return (
    <Stat
      label={t('energy.stored')}
      value={formatEnergy(energy, energyUnit, locale)}
      hint={hint}
      tone={forecast.kind === 'emptying' && forecast.msUntilEmpty < 3_600_000 ? 'warn' : undefined}
    />
  )
}

function BackLink() {
  const t = useT()
  return (
    <Link to="/energy" className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1.5 text-sm">
      <ArrowLeft className="size-4" /> {t('energy.back')}
    </Link>
  )
}

/** Generation and consumption per zone (name prefix before ':'). */
function ZonesSection({ devices: list }: { devices: FluxDevice[] }) {
  const t = useT()
  const { locale, energyUnit } = usePreferences()
  const summaries = zones(list)
  if (summaries.length <= 1 && summaries[0]?.zone === null) return null
  return (
    <Section title={t('energy.zones')}>
      <div className="grid min-w-0 gap-3 sm:grid-cols-2 xl:grid-cols-3" data-testid="zones">
        {summaries.map((z) => (
          <Card key={z.zone ?? '-'} className="min-w-0 gap-2 py-4">
            <CardContent className="min-w-0 space-y-2 px-4">
              <div className="flex min-w-0 items-center justify-between gap-2">
                <span className={cn('truncate text-sm font-medium', !z.zone && 'text-muted-foreground')}>
                  {z.zone ?? t('energy.noZone')}
                </span>
                <span className="text-muted-foreground shrink-0 text-xs">{t('energy.devices', { count: z.devices.length })}</span>
              </div>
              <div className="tabular grid grid-cols-2 gap-2 text-xs">
                <div className="min-w-0">
                  <p className="text-muted-foreground truncate">{t('energy.generation')}</p>
                  <p className="text-success truncate text-sm font-semibold">{formatRate(z.generation, energyUnit, locale)}</p>
                </div>
                <div className="min-w-0">
                  <p className="text-muted-foreground truncate">{t('energy.consumption')}</p>
                  <p className="text-primary truncate text-sm font-semibold">{formatRate(z.consumption, energyUnit, locale)}</p>
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>
    </Section>
  )
}

function DevicesSection({ devices: list }: { devices: FluxDevice[] }) {
  const t = useT()
  const [type, setType] = useState<DeviceType | 'all'>('all')
  const [query, setQuery] = useState('')
  const q = query.trim().toLowerCase()
  const shown = list.filter(
    (d) =>
      (type === 'all' || d.type === type) &&
      (!q || `${d.zone ?? ''} ${d.label} ${d.pos} ${d.dimension}`.toLowerCase().includes(q)),
  )
  const zoneNames = [...new Set(list.map((d) => d.zone).filter((z): z is string => !!z))].sort()
  const counts = Object.fromEntries(TYPES.map((ty) => [ty, list.filter((d) => d.type === ty).length]))

  return (
    <Section title={t('energy.devices', { count: list.length })}>
      <div className="mb-3 flex min-w-0 flex-col gap-2 sm:flex-row sm:items-center">
        <div className="bg-muted flex min-w-0 flex-wrap gap-1 rounded-lg p-1" role="tablist">
          {(['all', ...TYPES] as const).map((ty) => (
            <button
              key={ty}
              type="button"
              role="tab"
              aria-selected={type === ty}
              onClick={() => setType(ty)}
              className={cn(
                'shrink-0 rounded-md px-2.5 py-1 text-xs font-medium whitespace-nowrap transition-colors',
                type === ty ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {ty === 'all' ? t('energy.all') : t(`energy.type.${ty}`)}
              {ty !== 'all' && <span className="text-muted-foreground ml-1">{counts[ty]}</span>}
            </button>
          ))}
        </div>
        <div className="relative min-w-0 sm:ml-auto sm:w-64">
          <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2" />
          <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t('energy.search')} className="pl-8" />
        </div>
      </div>
      {shown.length === 0 ? (
        <p className="text-muted-foreground text-sm">{t('energy.noDevices')}</p>
      ) : (
        <Card className="min-w-0 py-1">
          <ul className="divide-border divide-y" data-testid="device-list">
            {shown.map((d) => (
              <DeviceRow key={d.id} device={d} zoneNames={zoneNames} />
            ))}
          </ul>
        </Card>
      )}
    </Section>
  )
}

function DeviceRow({ device: d, zoneNames }: { device: FluxDevice; zoneNames: string[] }) {
  const t = useT()
  const { locale, energyUnit } = usePreferences()
  const [editing, setEditing] = useState(false)
  const Icon = TYPE_ICON[d.type]
  if (editing) return <ZoneEditor device={d} zoneNames={zoneNames} onDone={() => setEditing(false)} />
  // Plugs take energy in, points deliver it; storages show the signed flow Flux reports.
  const flow =
    d.type === 'storage' ? d.transfer : d.type === 'controller' ? 0 : Math.abs(d.transfer)
  return (
    <li className={cn('flex min-w-0 items-center gap-3 px-4 py-2.5', !d.loaded && 'opacity-60')}>
      <Icon
        className={cn(
          'size-4 shrink-0',
          d.type === 'plug' ? 'text-success' : d.type === 'point' ? 'text-primary' : 'text-muted-foreground',
        )}
      />
      <div className="min-w-0 flex-1">
        <p className="flex min-w-0 items-center gap-2 text-sm font-medium">
          <span className="truncate">{d.label}</span>
          {d.zone && (
            <span className="text-muted-foreground hidden truncate text-xs font-normal sm:inline" title={d.zoneOverridden ? t('energy.zoneManual') : undefined}>
              {d.zone}
              {d.zoneOverridden && ' *'}
            </span>
          )}
        </p>
        <p className="text-muted-foreground truncate text-xs">
          {d.pos} · {d.dimension} · {t('energy.priority')} {d.priority}
          {!d.loaded && ` · ${t('energy.unloaded')}`}
          {d.surge && ` · ${t('energy.surge')}`}
        </p>
      </div>
      {d.canEdit && (
        <Button variant="ghost" size="icon" className="size-8 shrink-0" onClick={() => setEditing(true)} aria-label={t('energy.editZone')}>
          <Pencil className="size-3.5" />
        </Button>
      )}
      <div className="tabular shrink-0 text-right">
        {d.type !== 'controller' && <p className="text-sm font-semibold">{formatRate(flow, energyUnit, locale)}</p>}
        {d.type === 'storage' && (
          <p className="text-muted-foreground text-xs">{formatEnergy(d.buffer, energyUnit, locale)}</p>
        )}
        {d.type !== 'storage' && d.type !== 'controller' && (
          <p className="text-muted-foreground text-xs">
            {d.limit > 0 ? `≤ ${formatRate(d.limit, energyUnit, locale)}` : t('energy.noLimit')}
          </p>
        )}
      </div>
    </li>
  )
}

/** Inline editor for the zone of one device. Saving an empty value resets to the name prefix. */
function ZoneEditor({ device, zoneNames, onDone }: { device: FluxDevice; zoneNames: string[]; onDone: () => void }) {
  const t = useT()
  const save = useSetOverride('flux')
  const [value, setValue] = useState(device.zone ?? '')
  const listId = `zones-${device.id}`
  const submit = (event: FormEvent) => {
    event.preventDefault()
    save.mutate({ resource: device.resource.ref.key, key: 'zone', value: value.trim() || null }, { onSuccess: onDone })
  }
  return (
    <li className="min-w-0 px-4 py-2.5" data-testid="zone-editor">
      <p className="text-muted-foreground mb-1.5 truncate text-xs">
        {t('energy.editZone')}: <span className="text-foreground font-medium">{device.label}</span>
      </p>
      <form onSubmit={submit} className="flex min-w-0 flex-wrap gap-2">
        <Input
          autoFocus
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder={t('energy.zonePlaceholder')}
          maxLength={48}
          list={listId}
          className="min-w-0 flex-1 basis-48"
          aria-label={t('energy.zone')}
        />
        <datalist id={listId}>
          {zoneNames.map((z) => (
            <option key={z} value={z} />
          ))}
        </datalist>
        <div className="flex shrink-0 gap-1">
          <Button type="submit" size="sm" disabled={save.isPending}>
            <Check /> {t('common.save')}
          </Button>
          {device.zoneOverridden && (
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={save.isPending}
              onClick={() => save.mutate({ resource: device.resource.ref.key, key: 'zone', value: null }, { onSuccess: onDone })}
            >
              {t('common.reset')}
            </Button>
          )}
          <Button type="button" size="sm" variant="ghost" onClick={onDone} aria-label={t('common.cancel')}>
            <X />
          </Button>
        </div>
      </form>
    </li>
  )
}
