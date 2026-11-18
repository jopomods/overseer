import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from 'recharts'
import { api } from '@/api/client'
import { useSessions, type Sessions } from '@/api/hooks'
import { Segmented } from '@/components/history-chart'
import { ChartContainer, ChartTooltip } from '@/components/ui/chart'
import { Skeleton } from '@/components/ui/skeleton'
import { formatBuckets } from '@/lib/ae2'
import { relativeLabel } from '@/lib/chart'
import { formatNumber, formatSi } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { usePreferences, type Locale } from '@/lib/preferences'

/** Inventory changes are kept for the raw retention (48 h by default). */
const RANGES = [
  { ms: 15 * 60_000, label: '15 min' },
  { ms: 3_600_000, label: '1 h' },
  { ms: 6 * 3_600_000, label: '6 h' },
  { ms: 24 * 3_600_000, label: '24 h' },
  { ms: 48 * 3_600_000, label: '48 h' },
]

interface Point {
  x: number
  t: number
  amount: number
}

/**
 * How the amount of one item moved, minute by minute, from the inventory scans: for every item,
 * watched or not. A step line: the amount holds until the next minute it changed.
 */
export function ItemHistory({ resource, item, kind }: { resource: string; item: string; kind: string }) {
  const t = useT()
  const { locale, axis } = usePreferences()
  const [range, setRange] = useState(RANGES[1].ms)
  const sessions = useSessions()
  const now = sessions.data?.uptimeMillis
  const history = useQuery({
    queryKey: ['inventory-history', resource, item, range],
    queryFn: async () => {
      const { data, response } = await api.GET('/api/v1/inventory/history', {
        params: { query: { resource, item, from: Math.max(0, (now ?? 0) - range), to: now } },
      })
      if (!response.ok || !data) throw new Error(String(response.status))
      return data
    },
    enabled: now !== undefined,
    refetchInterval: 30_000,
    placeholderData: (previous) => previous,
  })

  const rows = useMemo<Point[]>(() => {
    const data = history.data
    if (!data) return []
    const toX = (u: number) => (axis === 'uptime' ? u : wallOf(u, sessions.data))
    const result: Point[] = [{ x: toX(data.from), t: data.from, amount: data.start }]
    for (const [bucket, amount] of data.points) result.push({ x: toX(bucket), t: bucket, amount })
    const end = data.now ?? result[result.length - 1].amount
    result.push({ x: toX(data.to), t: data.to, amount: end })
    return result
  }, [history.data, axis, sessions.data])
  const fmt = (v: number) => (kind === 'fluid' ? `${formatBuckets(v, locale)} B` : formatSi(v, locale))

  return (
    <div className="min-w-0 space-y-2" data-testid="item-history">
      <Segmented value={String(range)} options={RANGES.map((r) => ({ value: String(r.ms), label: r.label }))} onChange={(v) => setRange(Number(v))} />
      {history.isPending ? (
        <Skeleton className="h-40 w-full" />
      ) : (
        <ChartContainer config={{}} className="aspect-auto h-40 w-full">
          <AreaChart data={rows} margin={{ top: 8, right: 4, bottom: 0, left: 4 }}>
            <CartesianGrid vertical={false} strokeDasharray="3 3" />
            <XAxis
              dataKey="x"
              type="number"
              domain={['dataMin', 'dataMax']}
              tickLine={false}
              axisLine={false}
              minTickGap={40}
              tickFormatter={(x: number) => label(x, axis === 'uptime', now ?? 0, locale)}
            />
            <YAxis width={44} tickLine={false} axisLine={false} tick={{ fontSize: 11 }} tickFormatter={fmt} domain={['auto', 'auto']} />
            <ChartTooltip
              cursor={{ stroke: 'var(--border)' }}
              content={({ active, payload }) => {
                const row = payload?.[0]?.payload as Point | undefined
                if (!active || !row) return null
                return (
                  <div className="bg-popover text-popover-foreground border-border rounded-md border px-3 py-2 text-xs shadow-md">
                    <p className="text-muted-foreground">{label(row.x, axis === 'uptime', now ?? 0, locale)}</p>
                    <p className="tabular font-medium">
                      {kind === 'fluid' ? `${formatNumber(row.amount, locale)} mB` : formatNumber(row.amount, locale)}
                    </p>
                  </div>
                )
              }}
            />
            <Area dataKey="amount" type="stepAfter" stroke="var(--chart-2)" fill="var(--chart-2)" fillOpacity={0.2} strokeWidth={1.5} isAnimationActive={false} />
          </AreaChart>
        </ChartContainer>
      )}
      <p className="text-muted-foreground text-xs">{t('inv.historyHint')}</p>
    </div>
  )
}

function label(x: number, uptime: boolean, now: number, locale: Locale) {
  return uptime ? relativeLabel(x - now, locale) : new Date(x).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })
}

/** Uptime to wall time through the session it falls into. */
function wallOf(uptime: number, sessions: Sessions | undefined): number {
  const list = sessions?.sessions ?? []
  for (let i = list.length - 1; i >= 0; i--) {
    if (uptime >= list[i].startedUptime) return Date.parse(list[i].startedAt) + (uptime - list[i].startedUptime)
  }
  return Date.now() - ((sessions?.uptimeMillis ?? uptime) - uptime)
}
