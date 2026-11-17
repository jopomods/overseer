import { useMemo, useState } from 'react'
import { Area, AreaChart, CartesianGrid, ReferenceArea, ReferenceLine, XAxis, YAxis } from 'recharts'
import { useHistory, useSessions, type History } from '@/api/hooks'
import { Card, CardContent } from '@/components/ui/card'
import { ChartContainer, ChartTooltip, type ChartConfig } from '@/components/ui/chart'
import { Skeleton } from '@/components/ui/skeleton'
import { offlineMarkers, RANGES, relativeLabel, wallLabel } from '@/lib/chart'
import { formatDuration, formatSi } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { setPreferences, usePreferences, type TimeAxis } from '@/lib/preferences'
import { cn } from '@/lib/utils'

/**
 * History chart for one resource (ADR-0005, ADR-0009).
 *
 * Two time axes:
 * - server uptime (default): offline time is collapsed; restarts show a dashed marker with
 *   the real offline duration;
 * - calendar time: offline periods appear as shaded gaps.
 */
export interface ChartSeries {
  metric: string
  label: string
  color: string
}

interface Row {
  x: number
  t: number
  wall: number
  [metric: string]: number | null
}

export function HistoryChart({
  resource,
  series,
  format,
  testId,
}: {
  resource: string
  series: ChartSeries[]
  format: (value: number) => string
  testId?: string
}) {
  const t = useT()
  const { locale, axis } = usePreferences()
  const [range, setRange] = useState(RANGES[2].ms)
  const sessions = useSessions()
  const history = useHistory(
    resource,
    series.map((s) => s.metric),
    range,
    sessions.data?.uptimeMillis,
  )

  const rows = useMemo(() => toRows(history.data, axis), [history.data, axis])
  const markers = useMemo(() => offlineMarkers(sessions.data, history.data), [sessions.data, history.data])
  const config: ChartConfig = Object.fromEntries(series.map((s) => [s.metric, { label: s.label, color: s.color }]))
  const now = sessions.data?.uptimeMillis ?? 0

  return (
    <Card className="min-w-0 gap-3 py-4" data-testid={testId}>
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
          {series.map((s) => (
            <span key={s.metric} className="text-muted-foreground flex min-w-0 items-center gap-1.5 text-xs">
              <span className="size-2.5 shrink-0 rounded-sm" style={{ backgroundColor: s.color }} />
              <span className="truncate">{s.label}</span>
            </span>
          ))}
        </div>

        {history.isPending || sessions.isPending ? (
          <Skeleton className="h-56 w-full" />
        ) : rows.length === 0 ? (
          <p className="text-muted-foreground flex h-56 items-center justify-center text-sm">{t('chart.noData')}</p>
        ) : (
          <ChartContainer config={config} className="aspect-auto h-56 w-full">
            <AreaChart data={rows} margin={{ top: 8, right: 4, bottom: 0, left: 4 }}>
              <defs>
                {series.map((s) => (
                  <linearGradient key={s.metric} id={`fill-${s.metric}`} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor={s.color} stopOpacity={0.35} />
                    <stop offset="95%" stopColor={s.color} stopOpacity={0.02} />
                  </linearGradient>
                ))}
              </defs>
              <CartesianGrid vertical={false} strokeDasharray="3 3" />
              <XAxis
                dataKey="x"
                type="number"
                scale="linear"
                domain={['dataMin', 'dataMax']}
                tickLine={false}
                axisLine={false}
                minTickGap={40}
                tickFormatter={(x: number) => (axis === 'uptime' ? relativeLabel(x - now, locale) : wallLabel(x, range, locale))}
              />
              {/* Axis: number with SI prefix only; the unit is in the tooltip. Keeps labels on one line. */}
              <YAxis
                width={44}
                tickLine={false}
                axisLine={false}
                tickFormatter={(v: number) => formatSi(v, locale, 2)}
                tick={{ fontSize: 11 }}
              />
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
              {axis === 'calendar' &&
                markers.map((m) => (
                  <ReferenceArea key={m.session} x1={m.wallFrom} x2={m.wallTo} fill="var(--muted)" fillOpacity={0.5} ifOverflow="hidden" />
                ))}
              <ChartTooltip
                cursor={{ stroke: 'var(--border)' }}
                content={({ active, payload }) => {
                  const row = payload?.[0]?.payload as Row | undefined
                  if (!active || !row) return null
                  return (
                    <div className="bg-popover text-popover-foreground border-border min-w-40 rounded-md border px-3 py-2 text-xs shadow-md">
                      <p className="font-medium">{new Date(row.wall).toLocaleString(locale)}</p>
                      <p className="text-muted-foreground mb-1.5">{t('chart.uptimeAgo', { age: formatDuration(now - row.t, locale) })}</p>
                      {series.map((s) => (
                        <p key={s.metric} className="tabular flex items-center justify-between gap-4">
                          <span className="flex items-center gap-1.5">
                            <span className="size-2 rounded-sm" style={{ backgroundColor: s.color }} />
                            {s.label}
                          </span>
                          <span className="font-medium">{row[s.metric] == null ? '–' : format(row[s.metric] as number)}</span>
                        </p>
                      ))}
                    </div>
                  )
                }}
              />
              {/* Later series are dashed so identical series (e.g. Flux input = output) stay visible. */}
              {series.map((s, i) => (
                <Area
                  key={s.metric}
                  dataKey={s.metric}
                  type="stepAfter"
                  stroke={s.color}
                  strokeWidth={1.5}
                  strokeDasharray={i > 0 ? '6 4' : undefined}
                  fill={`url(#fill-${s.metric})`}
                  isAnimationActive={false}
                  connectNulls={false}
                />
              ))}
            </AreaChart>
          </ChartContainer>
        )}
      </CardContent>
    </Card>
  )
}

export function Segmented<T extends string>({
  value,
  options,
  onChange,
}: {
  value: T
  options: { value: T; label: string }[]
  onChange: (value: T) => void
}) {
  return (
    <div className="bg-muted flex min-w-0 flex-wrap gap-0.5 rounded-lg p-0.5" role="radiogroup">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={value === o.value}
          onClick={() => onChange(o.value)}
          className={cn(
            'rounded-md px-2 py-0.5 text-xs font-medium whitespace-nowrap transition-colors',
            value === o.value ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

/** Merges series into rows keyed by bucket; on the calendar axis inserts null rows at offline gaps. */
function toRows(history: History | undefined, axis: TimeAxis): Row[] {
  if (!history) return []
  const byT = new Map<number, Row>()
  for (const s of history.series) {
    for (const p of s.points) {
      const wall = Date.parse(p.wall)
      const row = byT.get(p.t) ?? { x: axis === 'uptime' ? p.t : wall, t: p.t, wall }
      row[s.metric] = p.avg
      byT.set(p.t, row)
    }
  }
  const rows = [...byT.values()].sort((a, b) => a.t - b.t)
  if (axis === 'uptime') return rows
  // Break the line where calendar time jumps further than the bucket (server was offline).
  const result: Row[] = []
  for (let i = 0; i < rows.length; i++) {
    const prev = rows[i - 1]
    if (prev && rows[i].wall - prev.wall > history.bucketMillis * 1.5) {
      const gap: Row = { x: prev.wall + history.bucketMillis, t: prev.t, wall: prev.wall + history.bucketMillis }
      for (const s of history.series) gap[s.metric] = null
      result.push(gap)
    }
    result.push(rows[i])
  }
  return result
}

