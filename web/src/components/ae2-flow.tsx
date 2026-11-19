import { ChevronDown } from 'lucide-react'
import { useMemo, useState, useSyncExternalStore } from 'react'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Sankey, Tooltip, XAxis, YAxis } from 'recharts'
import { useFlowHistory, useFlows } from '@/api/hooks'
import { Segmented } from '@/components/history-chart'
import { ItemIcon } from '@/components/item-icon'
import { McText } from '@/components/mc-text'
import { Section } from '@/components/stat'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { ChartContainer } from '@/components/ui/chart'
import { formatBuckets, idPos, type Ae2Bus } from '@/lib/ae2'
import { relativeLabel } from '@/lib/chart'
import { byDevice, byItem, DEVICE_KINDS, idle, type DeviceAmount, type FlowRow, type ItemFlow } from '@/lib/flow'
import { formatSi } from '@/lib/format'
import { useT, type MessageKey, type Translate } from '@/lib/i18n'
import { plainText } from '@/lib/mc-text'
import { usePreferences, type Locale } from '@/lib/preferences'
import { cn } from '@/lib/utils'

const RANGES = [
  { ms: 15 * 60_000, label: '15 min' },
  { ms: 3_600_000, label: '1 h' },
  { ms: 6 * 3_600_000, label: '6 h' },
  { ms: 24 * 3_600_000, label: '24 h' },
  { ms: 48 * 3_600_000, label: '48 h' },
]
const MINUTE = 60_000
const SHOWN = 15
const SANKEY_SIDE = 8

type View = 'items' | 'devices'

/**
 * Where things enter and leave an ME network (ADR-0020): per item which devices bring it in and
 * take it out (Sankey), per device what it moves, and buses that moved nothing. Minute data, kept
 * for the raw retention.
 */
export function FlowSection({ grid, uptime, buses }: { grid: string; uptime: number | undefined; buses: Ae2Bus[] }) {
  const t = useT()
  const [range, setRange] = useState(RANGES[1].ms)
  const [view, setView] = useState<View>('items')
  // Whole minutes, so the query changes once a minute and not with every live update.
  const to = uptime === undefined ? undefined : Math.floor(uptime / MINUTE) * MINUTE + MINUTE
  const from = to === undefined ? undefined : Math.max(0, to - range)
  const flows = useFlows(grid, from, to)
  const data = flows.data?.rows
  const rows = useMemo(() => (data ?? []) as FlowRow[], [data])
  const items = useMemo(() => byItem(rows), [rows])
  const devices = useMemo(() => byDevice(rows), [rows])
  const quiet = useMemo(() => idle(buses.map((b) => b.id), rows), [buses, rows])
  const quietBuses = buses.filter((b) => quiet.has(b.id))

  return (
    <Section
      title={t('flow.title')}
      aside={<Segmented value={String(range)} options={RANGES.map((r) => ({ value: String(r.ms), label: r.label }))} onChange={(v) => setRange(Number(v))} />}
    >
      <Card className="min-w-0 gap-3 px-4 py-4" data-testid="flow">
        <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
          <Segmented<View>
            value={view}
            onChange={setView}
            options={[
              { value: 'items', label: t('flow.byItem') },
              { value: 'devices', label: t('flow.byDevice') },
            ]}
          />
          <span className="text-muted-foreground text-xs">{t('flow.hint')}</span>
        </div>
        {flows.isPending ? (
          <p className="text-muted-foreground text-sm">{t('common.loading')}</p>
        ) : rows.length === 0 ? (
          <p className="text-muted-foreground text-sm">{t('flow.none')}</p>
        ) : view === 'items' ? (
          <ItemList grid={grid} items={items} from={from} to={to} />
        ) : (
          <DeviceList grid={grid} devices={devices} from={from} to={to} />
        )}
      </Card>
      {quietBuses.length > 0 && rows.length > 0 && (
        <p className="text-muted-foreground mt-2 text-xs" data-testid="flow-idle">
          {t('flow.idle', { count: quietBuses.length })}:{' '}
          {quietBuses
            .slice(0, 8)
            .map((b) => plainText(b.name ?? b.target) || idPos(b.id.slice(0, b.id.lastIndexOf('/'))))
            .join(', ')}
          {quietBuses.length > 8 && ` +${quietBuses.length - 8}`}
        </p>
      )}
    </Section>
  )
}

function amountText(value: number, kind: string, locale: Locale, t: Translate) {
  return kind === 'fluid' ? `${formatBuckets(value, locale)} ${t('ae2.buckets')}` : formatSi(value, locale)
}

function kindLabel(kind: string, t: Translate) {
  return (DEVICE_KINDS as readonly string[]).includes(kind) ? t(`flow.kind.${kind}` as MessageKey) : kind.replace(/_/g, ' ')
}

/** Where a device is: a position, or nothing for players. */
function where(device: string) {
  if (!device.includes('@')) return ''
  const slash = device.indexOf('/', device.indexOf('@'))
  return idPos(slash < 0 ? device : device.slice(0, slash))
}

function InOut({ inserted, extracted, kind }: { inserted: number; extracted: number; kind: string }) {
  const t = useT()
  const { locale } = usePreferences()
  return (
    <span className="tabular flex shrink-0 gap-2 text-xs">
      <span className={cn(inserted > 0 ? 'text-success' : 'text-muted-foreground')} title={t('flow.in')}>
        {inserted > 0 ? `+${amountText(inserted, kind, locale, t)}` : '–'}
      </span>
      <span className={cn(extracted > 0 ? 'text-destructive' : 'text-muted-foreground')} title={t('flow.out')}>
        {extracted > 0 ? `−${amountText(extracted, kind, locale, t)}` : '–'}
      </span>
    </span>
  )
}

function ItemList({ grid, items, from, to }: { grid: string; items: ItemFlow[]; from?: number; to?: number }) {
  const t = useT()
  const [all, setAll] = useState(false)
  const [open, setOpen] = useState<string | null>(null)
  const shown = all ? items : items.slice(0, SHOWN)
  return (
    <div className="min-w-0">
      <ul className="divide-border min-w-0 divide-y" data-testid="flow-items">
        {shown.map((item) => (
          <li key={item.key} className="min-w-0 py-2">
            <button
              type="button"
              className="flex w-full min-w-0 items-center gap-2 text-left"
              aria-expanded={open === item.key}
              onClick={() => setOpen((o) => (o === item.key ? null : item.key))}
            >
              <ItemIcon stackKey={item.key} name={item.name} />
              <span className="min-w-0 flex-1 truncate text-sm font-medium">
                <McText text={item.name} />
              </span>
              <InOut inserted={item.inserted} extracted={item.extracted} kind={item.kind} />
              <ChevronDown className={cn('text-muted-foreground size-4 shrink-0 transition-transform', open === item.key && 'rotate-180')} />
            </button>
            {open === item.key && <ItemDetail grid={grid} item={item} from={from} to={to} />}
          </li>
        ))}
      </ul>
      {items.length > SHOWN && (
        <Button variant="ghost" size="sm" className="mt-1" onClick={() => setAll((a) => !a)}>
          {all ? t('cpu.fewer') : t('cpu.allLines', { count: items.length })}
        </Button>
      )}
    </div>
  )
}

function ItemDetail({ grid, item, from, to }: { grid: string; item: ItemFlow; from?: number; to?: number }) {
  const wide = useMediaQuery('(min-width: 640px)')
  return (
    <div className="mt-2 min-w-0 space-y-3" data-testid="flow-item-detail">
      {wide ? <FlowSankey item={item} /> : <SideLists item={item} />}
      <MinuteChart grid={grid} filter={{ item: item.key }} kind={item.kind} from={from} to={to} />
    </div>
  )
}

/** The busiest devices on each side; the rest folded into one node. */
function side(list: DeviceAmount[], more: (count: number) => string): Array<{ name: string; amount: number }> {
  const top = list.slice(0, SANKEY_SIDE).map((d) => ({ name: d.deviceName, amount: d.amount }))
  const rest = list.slice(SANKEY_SIDE)
  if (rest.length > 0) top.push({ name: more(rest.length), amount: rest.reduce((s, d) => s + d.amount, 0) })
  return top
}

function FlowSankey({ item }: { item: ItemFlow }) {
  const t = useT()
  const { locale } = usePreferences()
  const more = (count: number) => t('flow.moreDevices', { count })
  const sources = side(item.sources, more)
  const sinks = side(item.sinks, more)
  // Recharts needs both sides; a lone side gets an empty counterpart through the network node.
  const nodes = [
    ...sources.map((s) => ({ name: s.name, amount: s.amount, kind: 'source' as const })),
    { name: t('flow.network'), amount: Math.max(item.inserted, item.extracted), kind: 'network' as const },
    ...sinks.map((s) => ({ name: s.name, amount: s.amount, kind: 'sink' as const })),
  ]
  const network = sources.length
  const links = [
    ...sources.map((s, i) => ({ source: i, target: network, value: Math.max(s.amount, 1) })),
    ...sinks.map((s, i) => ({ source: network, target: network + 1 + i, value: Math.max(s.amount, 1) })),
  ]
  if (links.length === 0) return null
  const height = Math.max(160, Math.max(sources.length, sinks.length) * 40)
  return (
    <div style={{ height }} data-testid="flow-sankey">
      <ResponsiveContainer width="100%" height="100%">
        <Sankey
          data={{ nodes, links }}
          nodeWidth={10}
          nodePadding={24}
          sort={false}
          margin={{ top: 4, right: 150, bottom: 4, left: 150 }}
          link={{ stroke: 'var(--primary)', strokeOpacity: 0.25 }}
          node={(props: { x: number; y: number; width: number; height: number; index: number }) => {
            const node = nodes[props.index]
            const fill = node.kind === 'source' ? 'var(--success)' : node.kind === 'sink' ? 'var(--primary)' : 'var(--chart-5)'
            const left = node.kind === 'source'
            const labelX = left ? props.x - 6 : props.x + props.width + 6
            const plain = plainText(node.name)
            return (
              <g>
                <rect x={props.x} y={props.y} width={props.width} height={Math.max(props.height, 2)} fill={fill} rx={2} />
                {node.kind !== 'network' && (
                  <text x={labelX} y={props.y + props.height / 2} textAnchor={left ? 'end' : 'start'} dominantBaseline="middle" fontSize={12}>
                    <tspan fill="var(--foreground)">{plain.length > 22 ? `${plain.slice(0, 21)}…` : plain}</tspan>
                    <tspan x={labelX} dy={13} fill="var(--muted-foreground)" fontSize={10}>
                      {amountText(node.amount, item.kind, locale, t)}
                    </tspan>
                  </text>
                )}
              </g>
            )
          }}
        />
      </ResponsiveContainer>
    </div>
  )
}

/** Phone view: who brings it in, who takes it out. */
function SideLists({ item }: { item: ItemFlow }) {
  const t = useT()
  const { locale } = usePreferences()
  const total = Math.max(item.inserted, item.extracted, 1)
  const group = (title: string, list: DeviceAmount[], color: string) =>
    list.length > 0 && (
      <div className="min-w-0 space-y-1.5">
        <p className="text-muted-foreground text-xs font-semibold tracking-wide uppercase">{title}</p>
        {list.slice(0, SHOWN).map((d) => (
          <div key={d.device} className="min-w-0">
            <div className="tabular flex min-w-0 justify-between gap-3 text-sm">
              <span className="min-w-0 truncate">
                <McText text={d.deviceName} />
              </span>
              <span className="shrink-0 font-medium">{amountText(d.amount, item.kind, locale, t)}</span>
            </div>
            <div className="bg-muted mt-1 h-1.5 overflow-hidden rounded-full">
              <div className="h-full rounded-full" style={{ width: `${(d.amount / total) * 100}%`, backgroundColor: color }} />
            </div>
          </div>
        ))}
      </div>
    )
  return (
    <div className="space-y-3" data-testid="flow-sides">
      {group(t('flow.sources'), item.sources, 'var(--success)')}
      {group(t('flow.sinks'), item.sinks, 'var(--primary)')}
    </div>
  )
}

function DeviceList({ grid, devices, from, to }: { grid: string; devices: ReturnType<typeof byDevice>; from?: number; to?: number }) {
  const t = useT()
  const [all, setAll] = useState(false)
  const [open, setOpen] = useState<string | null>(null)
  const shown = all ? devices : devices.slice(0, SHOWN)
  return (
    <div className="min-w-0">
      <ul className="divide-border min-w-0 divide-y" data-testid="flow-devices">
        {shown.map((d) => (
          <li key={d.device} className="min-w-0 py-2">
            <button
              type="button"
              className="flex w-full min-w-0 items-center gap-2 text-left"
              aria-expanded={open === d.device}
              onClick={() => setOpen((o) => (o === d.device ? null : d.device))}
            >
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium">
                  <McText text={d.deviceName} />
                </span>
                <span className="text-muted-foreground block truncate text-xs">
                  {kindLabel(d.deviceKind, t)}
                  {where(d.device) && ` · ${where(d.device)}`}
                </span>
              </span>
              <span className="flex shrink-0 items-center gap-0.5">
                {d.items.slice(0, 4).map((i) => (
                  <span key={i.key} title={plainText(i.name)}>
                    <ItemIcon stackKey={i.key} name={i.name} className="size-5 [&>img]:size-5" />
                  </span>
                ))}
              </span>
              <ChevronDown className={cn('text-muted-foreground size-4 shrink-0 transition-transform', open === d.device && 'rotate-180')} />
            </button>
            {open === d.device && (
              <div className="mt-2 min-w-0 space-y-2" data-testid="flow-device-detail">
                <ul className="min-w-0 space-y-1">
                  {d.items.slice(0, 30).map((i) => (
                    <li key={i.key} className="flex min-w-0 items-center gap-1.5">
                      <ItemIcon stackKey={i.key} name={i.name} className="size-4 [&>img]:size-4" />
                      <span className="min-w-0 flex-1 truncate text-xs">
                        <McText text={i.name} />
                      </span>
                      <InOut inserted={i.inserted} extracted={i.extracted} kind={i.kind} />
                    </li>
                  ))}
                </ul>
                {d.items.length > 30 && <p className="text-muted-foreground text-xs">{t('flow.moreItems', { count: d.items.length - 30 })}</p>}
                <MinuteChart grid={grid} filter={{ device: d.device }} kind={d.items.every((i) => i.kind === 'fluid') ? 'fluid' : 'item'} from={from} to={to} />
              </div>
            )}
          </li>
        ))}
      </ul>
      {devices.length > SHOWN && (
        <Button variant="ghost" size="sm" className="mt-1" onClick={() => setAll((a) => !a)}>
          {all ? t('cpu.fewer') : t('cpu.allLines', { count: devices.length })}
        </Button>
      )}
    </div>
  )
}

/** In (up) and out (down) per minute. */
function MinuteChart({
  grid,
  filter,
  kind,
  from,
  to,
}: {
  grid: string
  filter: { item?: string; device?: string }
  kind: string
  from?: number
  to?: number
}) {
  const t = useT()
  const { locale } = usePreferences()
  const history = useFlowHistory(grid, filter, from, to)
  const rows = (history.data?.points ?? []).map(([bucket, inserted, extracted]) => ({ t: bucket, inserted, extracted: -extracted }))
  if (rows.length === 0) return null
  const fmt = (v: number) => amountText(Math.abs(v), kind, locale, t)
  return (
    <ChartContainer config={{}} className="aspect-auto h-36 w-full" data-testid="flow-minutes">
      <BarChart data={rows} stackOffset="sign" margin={{ top: 4, right: 4, bottom: 0, left: 4 }}>
        <CartesianGrid vertical={false} strokeDasharray="3 3" />
        <XAxis dataKey="t" type="number" domain={[from ?? 'dataMin', to ?? 'dataMax']} tickLine={false} axisLine={false} minTickGap={40} tickFormatter={(x: number) => relativeLabel(x - (to ?? x), locale)} />
        <YAxis width={44} tickLine={false} axisLine={false} tick={{ fontSize: 11 }} tickFormatter={fmt} />
        <Tooltip
          content={({ active, payload }) => {
            const row = payload?.[0]?.payload as { t: number; inserted: number; extracted: number } | undefined
            if (!active || !row) return null
            return (
              <div className="bg-popover text-popover-foreground border-border rounded-md border px-3 py-2 text-xs shadow-md">
                <p className="text-muted-foreground">{relativeLabel(row.t - (to ?? row.t), locale)}</p>
                <p className="text-success tabular">+{fmt(row.inserted)}</p>
                <p className="text-destructive tabular">−{fmt(row.extracted)}</p>
              </div>
            )
          }}
        />
        <Bar dataKey="inserted" stackId="flow" fill="var(--success)" isAnimationActive={false} />
        <Bar dataKey="extracted" stackId="flow" fill="var(--primary)" isAnimationActive={false} />
      </BarChart>
    </ChartContainer>
  )
}

function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (onChange) => {
      const media = window.matchMedia(query)
      media.addEventListener('change', onChange)
      return () => media.removeEventListener('change', onChange)
    },
    () => window.matchMedia(query).matches,
  )
}
