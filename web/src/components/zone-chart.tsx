import { useMemo, useState } from 'react'
import { Area, AreaChart, CartesianGrid, ReferenceLine, XAxis, YAxis } from 'recharts'
import { useHistory, useSessions, type History } from '@/api/hooks'
import { Segmented } from '@/components/history-chart'
import { Card, CardContent } from '@/components/ui/card'
import { ChartContainer, ChartTooltip } from '@/components/ui/chart'
import { Skeleton } from '@/components/ui/skeleton'
import { offlineMarkers, RANGES, relativeLabel, wallLabel } from '@/lib/chart'
import type { FluxDevice } from '@/lib/flux'
import { formatDuration, formatRate, formatSi } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { setPreferences, usePreferences, type TimeAxis } from '@/lib/preferences'

/**
 * Generation (up) and consumption (down) per zone over time, stacked and mirrored
 * (`stackOffset="sign"`). Zones are assigned by the devices' current zone. The six
 * largest zones get their own color, the rest is grouped as "other".
 */
const PALETTE = [
  'var(--chart-1)',
  'var(--chart-2)',
  'var(--chart-3)',
  'var(--chart-4)',
  'var(--chart-5)',
  'oklch(0.7 0.15 330)',
]
const OTHER_COLOR = 'var(--muted-foreground)'
const MAX_ZONES = 6

interface Row {
  x: number
  t: number
  wall: number
  [key: string]: number
}

interface ZoneKey {
  zone: string
  color: string
}

export function ZoneChart({ devices }: { devices: FluxDevice[] }) {
  const t = useT()
  const { locale, axis, energyUnit } = usePreferences()
  const [range, setRange] = useState(RANGES[2].ms)
  const sessions = useSessions()
  const flowing = useMemo(() => devices.filter((d) => d.type === 'plug' || d.type === 'point').slice(0, 250), [devices])
  const history = useHistory(
    flowing.map((d) => d.resource.ref.key),
    ['transfer'],
    range,
    sessions.data?.uptimeMillis,
  )
  const noZone = t('energy.noZone')
  const other = t('energy.otherZones')
  const { rows, zones } = useMemo(
    () => build(history.data, flowing, axis, noZone, other),
    [history.data, flowing, axis, noZone, other],
  )
  const markers = useMemo(() => offlineMarkers(sessions.data, history.data), [sessions.data, history.data])
  const now = sessions.data?.uptimeMillis ?? 0

  return (
    <Card className="min-w-0 gap-3 py-4" data-testid="zone-chart">
      <CardContent className="min-w-0 space-y-3 px-4">
        <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
          <Segmented
            value={String(range)}
            options={RANGES.map((r) => ({ value: String(r.ms), label: locale === 'de' ? r.label.replace(' d', ' T') : r.label }))}
            onChange={(v) => setRange(Number(v))}
          />
          <Segmented<TimeAxis>
            value={axis}
            options={[
              { value: 'uptime', label: t('chart.uptime') },
              { value: 'calendar', label: t('chart.calendar') },
            ]}
            onChange={(value) => setPreferences({ axis: value })}
          />
        </div>
        <div className="flex min-w-0 flex-wrap gap-x-4 gap-y-1">
          {zones.map((z) => (
            <span key={z.zone} className="text-muted-foreground flex min-w-0 items-center gap-1.5 text-xs">
              <span className="size-2.5 shrink-0 rounded-sm" style={{ backgroundColor: z.color }} />
              <span className="max-w-40 truncate">{z.zone}</span>
            </span>
          ))}
        </div>
        <p className="text-muted-foreground text-xs">{t('energy.zoneChartHint')}</p>

        {history.isPending || sessions.isPending ? (
          <Skeleton className="h-64 w-full" />
        ) : rows.length === 0 ? (
          <p className="text-muted-foreground flex h-64 items-center justify-center text-sm">{t('chart.noData')}</p>
        ) : (
          <ChartContainer config={{}} className="aspect-auto h-64 w-full">
            <AreaChart data={rows} stackOffset="sign" margin={{ top: 8, right: 4, bottom: 0, left: 4 }}>
              <CartesianGrid vertical={false} strokeDasharray="3 3" />
              <XAxis
                dataKey="x"
                type="number"
                domain={['dataMin', 'dataMax']}
                tickLine={false}
                axisLine={false}
                minTickGap={40}
                tickFormatter={(x: number) => (axis === 'uptime' ? relativeLabel(x - now, locale) : wallLabel(x, range, locale))}
              />
              <YAxis width={44} tickLine={false} axisLine={false} tick={{ fontSize: 11 }} tickFormatter={(v: number) => formatSi(v, locale, 2)} />
              <ReferenceLine y={0} stroke="var(--border)" />
              {axis === 'uptime' &&
                markers.map((m) => (
                  <ReferenceLine
                    key={m.session}
                    x={m.uptime}
                    stroke="var(--muted-foreground)"
                    strokeDasharray="4 4"
                    label={{ value: `⏸ ${formatDuration(m.offlineMs, locale)}`, position: 'insideTopLeft', fontSize: 11, fill: 'var(--muted-foreground)' }}
                  />
                ))}
              <ChartTooltip
                cursor={{ stroke: 'var(--border)' }}
                content={({ active, payload }) => {
                  const row = payload?.[0]?.payload as Row | undefined
                  if (!active || !row) return null
                  return (
                    <div className="bg-popover text-popover-foreground border-border min-w-48 rounded-md border px-3 py-2 text-xs shadow-md">
                      <p className="mb-1.5 font-medium">{new Date(row.wall).toLocaleString(locale)}</p>
                      {zones.map((z) => {
                        const gen = row[`gen:${z.zone}`] ?? 0
                        const con = -(row[`con:${z.zone}`] ?? 0)
                        if (!gen && !con) return null
                        return (
                          <p key={z.zone} className="tabular flex items-center justify-between gap-4">
                            <span className="flex min-w-0 items-center gap-1.5">
                              <span className="size-2 shrink-0 rounded-sm" style={{ backgroundColor: z.color }} />
                              <span className="max-w-32 truncate">{z.zone}</span>
                            </span>
                            <span className="shrink-0">
                              {gen > 0 && <span className="text-success">+{formatRate(gen, energyUnit, locale)} </span>}
                              {con > 0 && <span className="text-primary">−{formatRate(con, energyUnit, locale)}</span>}
                            </span>
                          </p>
                        )
                      })}
                    </div>
                  )
                }}
              />
              {zones.flatMap((z) => [
                <Area
                  key={`gen:${z.zone}`}
                  dataKey={`gen:${z.zone}`}
                  stackId="flow"
                  type="stepAfter"
                  stroke={z.color}
                  fill={z.color}
                  fillOpacity={0.45}
                  strokeWidth={1}
                  isAnimationActive={false}
                />,
                <Area
                  key={`con:${z.zone}`}
                  dataKey={`con:${z.zone}`}
                  stackId="flow"
                  type="stepAfter"
                  stroke={z.color}
                  fill={z.color}
                  fillOpacity={0.25}
                  strokeWidth={1}
                  strokeDasharray="4 3"
                  isAnimationActive={false}
                />,
              ])}
            </AreaChart>
          </ChartContainer>
        )}
      </CardContent>
    </Card>
  )
}

/** Sums device series per zone and bucket: plugs as generation (+), points as consumption (−). */
function build(history: History | undefined, devices: FluxDevice[], axis: TimeAxis, noZone: string, other: string) {
  if (!history) return { rows: [] as Row[], zones: [] as ZoneKey[] }
  const byKey = new Map(devices.map((d) => [d.resource.ref.key, d]))
  const volume = new Map<string, number>()
  const perZone = new Map<number, Map<string, { gen: number; con: number }>>()
  const times = new Map<number, number>()

  for (const series of history.series) {
    const device = byKey.get(series.resource)
    if (!device) continue
    const zone = device.zone ?? noZone
    for (const p of series.points) {
      const value = Math.abs(p.avg)
      times.set(p.t, Date.parse(p.wall))
      const zones = perZone.get(p.t) ?? new Map()
      const entry = zones.get(zone) ?? { gen: 0, con: 0 }
      if (device.type === 'plug') entry.gen += value
      else entry.con += value
      zones.set(zone, entry)
      perZone.set(p.t, zones)
      volume.set(zone, (volume.get(zone) ?? 0) + value)
    }
  }

  const ranked = [...volume.entries()].sort((a, b) => b[1] - a[1]).map(([zone]) => zone)
  const shown = ranked.slice(0, ranked.length > MAX_ZONES ? MAX_ZONES - 1 : MAX_ZONES)
  const label = (zone: string) => (shown.includes(zone) ? zone : other)
  const zones: ZoneKey[] = shown.map((zone, i) => ({ zone, color: PALETTE[i % PALETTE.length] }))
  if (ranked.length > shown.length) zones.push({ zone: other, color: OTHER_COLOR })

  const rows: Row[] = [...perZone.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([t, zoneMap]) => {
      const wall = times.get(t) ?? 0
      const row: Row = { x: axis === 'uptime' ? t : wall, t, wall }
      for (const z of zones) {
        row[`gen:${z.zone}`] = 0
        row[`con:${z.zone}`] = 0
      }
      for (const [zone, { gen, con }] of zoneMap) {
        row[`gen:${label(zone)}`] += gen
        row[`con:${label(zone)}`] -= con
      }
      return row
    })
  return { rows, zones }
}
