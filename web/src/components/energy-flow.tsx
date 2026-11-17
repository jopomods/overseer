import { useSyncExternalStore } from 'react'
import { ResponsiveContainer, Sankey, Tooltip } from 'recharts'
import { Card, CardContent } from '@/components/ui/card'
import { flowGraph, type FlowGraph, type FluxDevice } from '@/lib/flux'
import { formatRate } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { usePreferences } from '@/lib/preferences'

/**
 * Where the energy of a network comes from and where it goes, per zone (ADR-0009).
 * Sankey on wider screens; on phones a compact list with bars, since a Sankey is not
 * readable at 360 px.
 */
export function EnergyFlow({ devices }: { devices: FluxDevice[] }) {
  const t = useT()
  const wide = useMediaQuery('(min-width: 640px)')
  const graph = flowGraph(devices, {
    network: t('energy.network'),
    noZone: t('energy.noZone'),
    storageIn: t('energy.storageCharging'),
    storageOut: t('energy.storageDischarging'),
  })
  return (
    <Card className="min-w-0 py-4" data-testid="energy-flow">
      <CardContent className="min-w-0 px-4">
        {graph.links.length === 0 ? (
          <p className="text-muted-foreground text-sm">{t('energy.noFlow')}</p>
        ) : wide ? (
          <FlowSankey graph={graph} />
        ) : (
          <FlowList graph={graph} />
        )}
      </CardContent>
    </Card>
  )
}

function FlowSankey({ graph }: { graph: FlowGraph }) {
  const { locale, energyUnit } = usePreferences()
  const height = Math.max(220, Math.max(countKind(graph, 'source'), countKind(graph, 'sink')) * 34)
  return (
    <div style={{ height }} data-testid="flow-sankey">
      <ResponsiveContainer width="100%" height="100%">
        <Sankey
          data={graph}
          nodeWidth={10}
          nodePadding={14}
          iterations={0}
          margin={{ top: 4, right: 170, bottom: 4, left: 170 }}
          link={{ stroke: 'var(--primary)', strokeOpacity: 0.25 }}
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
  const kind = graph.nodes[index]?.kind
  const color = kind === 'source' ? 'var(--success)' : kind === 'sink' ? 'var(--primary)' : 'var(--chart-5)'
  const left = kind === 'source'
  const labelX = left ? x - 8 : x + width + 8
  const name = payload.name.length > 22 ? `${payload.name.slice(0, 21)}…` : payload.name
  return (
    <g>
      <rect x={x} y={y} width={width} height={Math.max(height, 2)} fill={color} rx={2} />
      {kind !== 'network' && (
        <text x={labelX} y={y + height / 2} textAnchor={left ? 'end' : 'start'} dominantBaseline="middle" fontSize={12}>
          <tspan fill="var(--foreground)">{name}</tspan>
          <tspan x={labelX} dy={14} fill="var(--muted-foreground)" fontSize={11}>
            {formatRate(payload.value, energyUnit, locale)}
          </tspan>
        </text>
      )}
    </g>
  )
}

function FlowList({ graph }: { graph: FlowGraph }) {
  const t = useT()
  const { locale, energyUnit } = usePreferences()
  const network = graph.nodes.findIndex((n) => n.kind === 'network')
  const sources = graph.links.filter((l) => l.target === network).map((l) => ({ name: graph.nodes[l.source].name, value: l.value }))
  const sinks = graph.links.filter((l) => l.source === network).map((l) => ({ name: graph.nodes[l.target].name, value: l.value }))
  const group = (title: string, items: Array<{ name: string; value: number }>, color: string) => (
    <div className="min-w-0 space-y-2">
      <p className="text-muted-foreground text-xs font-semibold tracking-wide uppercase">{title}</p>
      {items.map((item) => (
        <div key={item.name} className="min-w-0">
          <div className="tabular flex min-w-0 justify-between gap-3 text-sm">
            <span className="min-w-0 truncate">{item.name}</span>
            <span className="shrink-0 font-medium">{formatRate(item.value, energyUnit, locale)}</span>
          </div>
          <div className="bg-muted mt-1 h-1.5 overflow-hidden rounded-full">
            <div className="h-full rounded-full" style={{ width: `${(item.value / graph.total) * 100}%`, backgroundColor: color }} />
          </div>
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

function countKind(graph: FlowGraph, kind: 'source' | 'sink') {
  return graph.nodes.filter((n) => n.kind === kind).length
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
