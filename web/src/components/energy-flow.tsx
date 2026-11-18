import { useState, useSyncExternalStore } from 'react'
import { ResponsiveContainer, Sankey, Tooltip } from 'recharts'
import { Segmented } from '@/components/history-chart'
import { Card, CardContent } from '@/components/ui/card'
import { flowGraph, type FlowGraph, type FlowNodeKind, type FluxDevice } from '@/lib/flux'
import { formatRate } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { setPreferences, usePreferences, type FlowDetail } from '@/lib/preferences'

/**
 * Where the energy of a network comes from and where it goes (ADR-0009).
 * Detail (saved per viewer): zone → network → zone, or device → zone → network → zone →
 * device with same-named devices grouped. Sankey on wider screens; on phones a list.
 */
export function EnergyFlow({ devices }: { devices: FluxDevice[] }) {
  const t = useT()
  const { flowDetail } = usePreferences()
  const wide = useMediaQuery('(min-width: 640px)')
  const graph = flowGraph(
    devices,
    {
      network: t('energy.network'),
      noZone: t('energy.noZone'),
      storageIn: t('energy.storageCharging'),
      storageOut: t('energy.storageDischarging'),
      more: (count) => t('energy.moreDevices', { count }),
    },
    flowDetail,
  )
  return (
    <Card className="min-w-0 gap-3 py-4" data-testid="energy-flow">
      <CardContent className="min-w-0 space-y-3 px-4">
        <Segmented<FlowDetail>
          value={flowDetail}
          options={[
            { value: 'zones', label: t('energy.flowZones') },
            { value: 'devices', label: t('energy.flowDevices') },
          ]}
          onChange={(value) => setPreferences({ flowDetail: value })}
        />
        {graph.links.length === 0 ? (
          <p className="text-muted-foreground text-sm">{t('energy.noFlow')}</p>
        ) : wide ? (
          <FlowSankey key={flowDetail} graph={graph} />
        ) : (
          <FlowList graph={graph} />
        )}
      </CardContent>
    </Card>
  )
}

/** Storage stands out from producers and consumers: charging amber, discharging blue. */
const STORAGE_COLOR = { charging: 'var(--warning)', discharging: 'var(--chart-2)' } as const

function nodeColor(node: { kind: FlowNodeKind; storage?: 'charging' | 'discharging' } | undefined) {
  if (!node) return COLOR.network
  return node.storage ? STORAGE_COLOR[node.storage] : COLOR[node.kind]
}

const COLOR: Record<FlowNodeKind, string> = {
  'device-source': 'var(--success)',
  source: 'var(--success)',
  network: 'var(--chart-5)',
  sink: 'var(--primary)',
  'device-sink': 'var(--primary)',
}

function FlowSankey({ graph }: { graph: FlowGraph }) {
  const { locale, energyUnit } = usePreferences()
  // Height follows the busiest column so labels do not collide.
  const perColumn = new Map<FlowNodeKind, number>()
  for (const n of graph.nodes) perColumn.set(n.kind, (perColumn.get(n.kind) ?? 0) + 1)
  // Each node needs room for a two-line label, even when its flow is tiny.
  const needed = Math.max(220, Math.max(...perColumn.values()) * 44)
  // Never shrink while shown: devices that pause for a second would otherwise change the page
  // height every update and make the page jump while scrolling.
  const [tallest, setTallest] = useState(needed)
  if (needed > tallest) setTallest(needed)
  const height = Math.max(tallest, needed)
  return (
    <div style={{ height }} data-testid="flow-sankey">
      <ResponsiveContainer width="100%" height="100%">
        <Sankey
          data={graph}
          nodeWidth={10}
          nodePadding={30}
          iterations={32}
          sort={false}
          margin={{ top: 4, right: 170, bottom: 4, left: 170 }}
          link={(props: LinkProps) => <FlowLink {...props} graph={graph} />}
          node={(props: NodeProps) => <FlowNode {...props} graph={graph} />}
        >
          <Tooltip
            content={({ payload }) => {
              const item = payload?.[0]
              if (!item) return null
              return (
                <div className="bg-popover text-popover-foreground border-border rounded-md border px-3 py-2 text-xs shadow-md">
                  <p className="font-medium">{String(item.name ?? '')}</p>
                  <p className="tabular">{formatRate(Number(item.value ?? 0), energyUnit, locale)}</p>
                </div>
              )
            }}
          />
        </Sankey>
      </ResponsiveContainer>
    </div>
  )
}

interface LinkProps {
  sourceX: number
  sourceY: number
  sourceControlX: number
  targetX: number
  targetY: number
  targetControlX: number
  linkWidth: number
  index: number
}

/** Links into or out of a storage take the storage colour, the rest stay neutral. */
function FlowLink({ sourceX, sourceY, sourceControlX, targetX, targetY, targetControlX, linkWidth, index, graph }: LinkProps & { graph: FlowGraph }) {
  const link = graph.links[index]
  const storage = link && (graph.nodes[link.source]?.storage ?? graph.nodes[link.target]?.storage)
  return (
    <path
      d={`M${sourceX},${sourceY} C${sourceControlX},${sourceY} ${targetControlX},${targetY} ${targetX},${targetY}`}
      fill="none"
      stroke={storage ? STORAGE_COLOR[storage] : 'var(--primary)'}
      strokeOpacity={storage ? 0.45 : 0.22}
      strokeWidth={Math.max(linkWidth, 1)}
    />
  )
}

interface NodeProps {
  x: number
  y: number
  width: number
  height: number
  index: number
  payload: { name: string; value: number }
}

function FlowNode({ x, y, width, height, index, payload, graph }: NodeProps & { graph: FlowGraph }) {
  const { locale, energyUnit } = usePreferences()
  const kind = graph.nodes[index]?.kind ?? 'network'
  const fill = nodeColor(graph.nodes[index])
  if (kind === 'network') return <rect x={x} y={y} width={width} height={Math.max(height, 2)} fill={fill} rx={2} />
  // Outer columns label outwards; inner columns (zones next to devices) label inwards with an
  // outline in the card colour so the text stays readable over the links.
  const hasDevices = graph.nodes.some((n) => n.kind === 'device-source' || n.kind === 'device-sink')
  const outer = kind.startsWith('device') || !hasDevices
  const leftSide = kind === 'device-source' || kind === 'source'
  const toLeft = outer ? leftSide : !leftSide
  const labelX = toLeft ? x - 6 : x + width + 6
  const max = outer ? 24 : 16
  const name = payload.name.length > max ? `${payload.name.slice(0, max - 1)}…` : payload.name
  return (
    <g>
      <rect x={x} y={y} width={width} height={Math.max(height, 2)} fill={fill} rx={2} />
      <text
        x={labelX}
        y={y + height / 2}
        textAnchor={toLeft ? 'end' : 'start'}
        dominantBaseline="middle"
        fontSize={outer ? 12 : 11}
        stroke={outer ? undefined : 'var(--card)'}
        strokeWidth={outer ? undefined : 3}
        paintOrder="stroke"
      >
        <tspan fill="var(--foreground)">{name}</tspan>
        <tspan x={labelX} dy={13} fill="var(--muted-foreground)" fontSize={10}>
          {formatRate(payload.value, energyUnit, locale)}
        </tspan>
      </text>
    </g>
  )
}

/** Phone view: zones with their devices (in device detail) as bars. */
function FlowList({ graph }: { graph: FlowGraph }) {
  const t = useT()
  const { locale, energyUnit } = usePreferences()
  const network = graph.nodes.findIndex((n) => n.kind === 'network')
  const valueOf = (from: number, to: number) => graph.links.find((l) => l.source === from && l.target === to)?.value ?? 0
  const children = (zone: number, kind: FlowNodeKind) =>
    graph.links
      .filter((l) => (kind === 'device-source' ? l.target === zone : l.source === zone))
      .map((l) => (kind === 'device-source' ? l.source : l.target))
      .filter((i) => graph.nodes[i].kind === kind)
      .map((i) => ({ name: graph.nodes[i].name, value: kind === 'device-source' ? valueOf(i, zone) : valueOf(zone, i) }))
  const sources = graph.links
    .filter((l) => l.target === network)
    .map((l) => ({ name: graph.nodes[l.source].name, value: l.value, devices: children(l.source, 'device-source') }))
  const sinks = graph.links
    .filter((l) => l.source === network)
    .map((l) => ({ name: graph.nodes[l.target].name, value: l.value, devices: children(l.target, 'device-sink') }))

  const bar = (name: string, value: number, color: string, small = false) => (
    <div key={name} className={small ? 'min-w-0 pl-3' : 'min-w-0'}>
      <div className={`tabular flex min-w-0 justify-between gap-3 ${small ? 'text-xs' : 'text-sm'}`}>
        <span className={`min-w-0 truncate ${small ? 'text-muted-foreground' : ''}`}>{name}</span>
        <span className="shrink-0 font-medium">{formatRate(value, energyUnit, locale)}</span>
      </div>
      <div className="bg-muted mt-1 h-1.5 overflow-hidden rounded-full">
        <div className="h-full rounded-full" style={{ width: `${(value / graph.total) * 100}%`, backgroundColor: color }} />
      </div>
    </div>
  )
  const group = (title: string, items: typeof sources, color: string) => (
    <div className="min-w-0 space-y-2">
      <p className="text-muted-foreground text-xs font-semibold tracking-wide uppercase">{title}</p>
      {items.map((item) => (
        <div key={item.name} className="min-w-0 space-y-1.5">
          {bar(item.name, item.value, color)}
          {item.devices.map((d) => bar(d.name, d.value, color, true))}
        </div>
      ))}
    </div>
  )
  return (
    <div className="space-y-4" data-testid="flow-list">
      {sources.length > 0 && group(t('energy.generation'), sources, 'var(--success)')}
      {sinks.length > 0 && group(t('energy.consumption'), sinks, 'var(--primary)')}
    </div>
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
