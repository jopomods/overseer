import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Play, Square, Timer } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from 'recharts'
import { api } from '@/api/client'
import { useHistory, useSessions, useSnapshot, type History, type Resource } from '@/api/hooks'
import { Segmented } from '@/components/history-chart'
import { McText } from '@/components/mc-text'
import { Section } from '@/components/stat'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { ChartContainer, ChartTooltip } from '@/components/ui/chart'
import { Skeleton } from '@/components/ui/skeleton'
import { relativeLabel, RANGES, wallLabel } from '@/lib/chart'
import { formatNumber, formatSi } from '@/lib/format'
import { useT, type Translate } from '@/lib/i18n'
import { usePreferences, type Locale } from '@/lib/preferences'
import { metric, ofType } from '@/lib/snapshot'

const PALETTE = ['var(--chart-1)', 'var(--chart-2)', 'var(--chart-3)', 'var(--chart-4)', 'var(--chart-5)', 'oklch(0.7 0.15 330)', 'oklch(0.75 0.12 200)', 'oklch(0.7 0.14 60)']
const DURATIONS = [30, 60, 300, 900, 3600]
const INTERVALS = [5, 10, 30, 60]
const TOTAL = 'profiler/total/all'

interface ProfilerState {
  mode: 'off' | 'timed' | 'continuous'
  until?: string
  intervalSeconds: number
  active: boolean
}

async function call<T>(promise: Promise<{ data?: T; response: Response }>): Promise<T> {
  const { data, response } = await promise
  if (!response.ok) throw new Error(String(response.status))
  return data as T
}

/**
 * Tick profiler (ADR-0014, admins only): start it for a while or keep it running; shows which
 * block and entity types cost the most tick time, over time (stacked) and as a live top list.
 */
export function ProfilerSection() {
  const t = useT()
  const { locale } = usePreferences()
  const client = useQueryClient()
  const state = useQuery({
    queryKey: ['profiler'],
    queryFn: () => call<ProfilerState>(api.GET('/api/v1/admin/profiler')),
    refetchInterval: 5_000,
  })
  const set = useMutation({
    mutationFn: (body: { mode: ProfilerState['mode']; durationSeconds?: number; intervalSeconds?: number }) =>
      call<ProfilerState>(api.PUT('/api/v1/admin/profiler', { body })),
    onSuccess: (data) => client.setQueryData(['profiler'], data),
  })
  const [duration, setDuration] = useState(60)
  const [interval, setInterval] = useState(10)
  const snapshot = useSnapshot('profiler')
  const sessions = useSessions()
  const [group, setGroup] = useState<'type' | 'mod'>('type')
  const typeResources = useMemo(() => ofType(snapshot.data, group), [snapshot.data, group])
  // Values arrive only with a measurement; between measurements (and after a stop) the newest
  // stored point is the last result.
  const latestHistory = useHistory(
    [TOTAL, ...typeResources.map((r) => r.ref.key)],
    ['tick_us', 'objects'],
    24 * 3_600_000,
    sessions.data?.uptimeMillis,
  )
  const latest = useMemo(() => lastValues(latestHistory.data), [latestHistory.data])
  const types = useMemo(
    () =>
      typeResources
        .map((r) => ({ r, us: metric(r, 'tick_us') ?? latest.get(`${r.ref.key}|tick_us`) ?? 0 }))
        .sort((a, b) => b.us - a.us),
    [typeResources, latest],
  )
  const total = snapshot.data?.resources.find((r) => r.ref.key === TOTAL)
  const totalUs = metric(total, 'tick_us') ?? latest.get(`${TOTAL}|tick_us`)
  const totalObjects = metric(total, 'objects') ?? latest.get(`${TOTAL}|objects`)
  const s = state.data

  return (
    <Section title={t('prof.title')} aside={<Badge variant="secondary">{t('common.adminOnly')}</Badge>}>
      <Card className="min-w-0 gap-3 py-4" data-testid="profiler">
        <CardContent className="min-w-0 space-y-3 px-4">
          <p className="text-muted-foreground text-xs">{t('prof.hint')}</p>
          <div className="flex min-w-0 flex-wrap items-center gap-x-4 gap-y-2">
            <span className="flex items-center gap-2 text-sm font-medium" data-testid="profiler-status">
              <span className={`size-2 rounded-full ${s?.active ? 'bg-success animate-pulse' : 'bg-muted-foreground'}`} />
              {status(s, t, locale)}
            </span>
            {s?.active && (
              <Button size="sm" variant="outline" onClick={() => set.mutate({ mode: 'off' })} disabled={set.isPending}>
                <Square /> {t('prof.stop')}
              </Button>
            )}
          </div>
          <div className="flex min-w-0 flex-wrap items-center gap-2">
            <span className="text-muted-foreground text-xs">{t('prof.duration')}</span>
            <Segmented
              value={String(duration)}
              options={DURATIONS.map((d) => ({ value: String(d), label: seconds(d, locale) }))}
              onChange={(v) => setDuration(Number(v))}
            />
            <span className="text-muted-foreground text-xs">{t('prof.interval')}</span>
            <Segmented
              value={String(interval)}
              options={INTERVALS.map((d) => ({ value: String(d), label: seconds(d, locale) }))}
              onChange={(v) => setInterval(Number(v))}
            />
          </div>
          <div className="flex min-w-0 flex-wrap gap-2">
            <Button size="sm" onClick={() => set.mutate({ mode: 'timed', durationSeconds: duration, intervalSeconds: interval })} disabled={set.isPending}>
              <Play /> {t('prof.start', { duration: seconds(duration, locale) })}
            </Button>
            <Button size="sm" variant="outline" onClick={() => set.mutate({ mode: 'continuous', intervalSeconds: interval })} disabled={set.isPending}>
              <Timer /> {t('prof.continuous')}
            </Button>
          </div>
          {totalUs !== undefined && (
            <p className="text-muted-foreground tabular text-xs">
              {t('prof.total', {
                ms: ms(totalUs, locale),
                objects: formatNumber(totalObjects ?? 0, locale),
              })}
            </p>
          )}
        </CardContent>
      </Card>

      {types.length > 0 && (
        <div className="mt-3 grid min-w-0 gap-3 xl:grid-cols-2">
          <StackedTypes
            key={group}
            types={types.slice(0, PALETTE.length).map((x) => x.r)}
            group={group}
            onGroup={setGroup}
          />
          <TopList />
        </div>
      )}
    </Section>
  )
}

function seconds(s: number, locale: Locale) {
  if (s < 60) return `${s} s`
  if (s < 3600) return `${s / 60} min`
  return `${formatNumber(s / 3600, locale)} h`
}

function status(s: ProfilerState | undefined, t: Translate, locale: Locale) {
  if (!s) return '…'
  if (!s.active) return t('prof.off')
  if (s.mode === 'continuous') return t('prof.runningContinuous', { interval: seconds(s.intervalSeconds, locale) })
  return t('prof.runningUntil', { until: s.until ? new Date(s.until).toLocaleTimeString(locale) : '?' })
}

function label(r: Resource, t: Translate) {
  if (r.ref.id === 'other') return t('prof.other')
  return r.attributes.name || r.ref.id.slice(r.ref.id.indexOf(':') + 1)
}

/** µs per tick of the largest types, stacked over time. */
function StackedTypes({
  types,
  group,
  onGroup,
}: {
  types: Resource[]
  group: 'type' | 'mod'
  onGroup: (group: 'type' | 'mod') => void
}) {
  const t = useT()
  const { locale, axis } = usePreferences()
  const [range, setRange] = useState(RANGES[0].ms)
  const sessions = useSessions()
  const keys = types.map((r) => r.ref.key)
  const history = useHistory(keys, ['tick_us'], range, sessions.data?.uptimeMillis)
  const rows = useMemo(() => toRows(history.data, axis), [history.data, axis])
  const now = sessions.data?.uptimeMillis ?? 0
  return (
    <Card className="min-w-0 gap-3 py-4" data-testid="profiler-chart">
      <CardContent className="min-w-0 space-y-3 px-4">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
        <Segmented
            value={String(range)}
            options={RANGES.map((r) => ({ value: String(r.ms), label: locale === 'de' ? r.label.replace(' d', ' T') : r.label }))}
            onChange={(v) => setRange(Number(v))}
          />
          <Segmented<'type' | 'mod'>
            value={group}
            options={[
              { value: 'type', label: t('prof.byType') },
              { value: 'mod', label: t('prof.byMod') },
            ]}
            onChange={onGroup}
          />
        </div>
        <div className="flex min-w-0 flex-wrap gap-x-4 gap-y-1">
          {types.map((r, i) => (
            <span key={r.ref.key} className="text-muted-foreground flex min-w-0 items-center gap-1.5 text-xs">
              <span className="size-2.5 shrink-0 rounded-sm" style={{ backgroundColor: PALETTE[i] }} />
              <span className="max-w-40 truncate">
                <McText text={label(r, t)} />
              </span>
            </span>
          ))}
        </div>
        {history.isPending ? (
          <Skeleton className="h-64 w-full" />
        ) : rows.length === 0 ? (
          <p className="text-muted-foreground flex h-64 items-center justify-center text-sm">{t('chart.noData')}</p>
        ) : (
          <ChartContainer config={{}} className="aspect-auto h-64 w-full">
            <AreaChart data={rows} margin={{ top: 8, right: 4, bottom: 0, left: 4 }}>
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
              <YAxis width={44} tickLine={false} axisLine={false} tick={{ fontSize: 11 }} tickFormatter={(v: number) => formatSi(v / 1000, locale, 2)} />
              <ChartTooltip
                cursor={{ stroke: 'var(--border)' }}
                content={({ active, payload }) => {
                  const row = payload?.[0]?.payload as Row | undefined
                  if (!active || !row) return null
                  return (
                    <div className="bg-popover text-popover-foreground border-border min-w-48 rounded-md border px-3 py-2 text-xs shadow-md">
                      <p className="mb-1.5 font-medium">{new Date(row.wall).toLocaleString(locale)}</p>
                      {types.map((r, i) => (
                        <p key={r.ref.key} className="tabular flex items-center justify-between gap-4">
                          <span className="flex min-w-0 items-center gap-1.5">
                            <span className="size-2 shrink-0 rounded-sm" style={{ backgroundColor: PALETTE[i] }} />
                            <span className="max-w-40 truncate">
                              <McText text={label(r, t)} />
                            </span>
                          </span>
                          <span className="shrink-0">{ms(row[r.ref.key] ?? 0, locale)}</span>
                        </p>
                      ))}
                    </div>
                  )
                }}
              />
              {types.map((r, i) => (
                <Area
                  key={r.ref.key}
                  dataKey={r.ref.key}
                  stackId="tick"
                  type="monotone"
                  stroke={PALETTE[i]}
                  fill={PALETTE[i]}
                  fillOpacity={0.4}
                  strokeWidth={1}
                  isAnimationActive={false}
                />
              ))}
            </AreaChart>
          </ChartContainer>
        )}
      </CardContent>
    </Card>
  )
}

/** Newest point per resource and metric, keyed "resource|metric". */
function lastValues(history: History | undefined): Map<string, number> {
  const result = new Map<string, number>()
  for (const s of history?.series ?? []) {
    const last = s.points.at(-1)
    if (last) result.set(`${s.resource}|${s.metric}`, last.avg)
  }
  return result
}

/** µs as milliseconds per tick with sensible precision. */
function ms(us: number, locale: Locale) {
  return `${formatNumber(us / 1000, locale, us < 1000 ? 2 : 1)} ms`
}

interface Row {
  x: number
  t: number
  wall: number
  [key: string]: number
}

function toRows(history: History | undefined, axis: string): Row[] {
  if (!history) return []
  const byT = new Map<number, Row>()
  for (const s of history.series) {
    for (const p of s.points) {
      const wall = Date.parse(p.wall)
      const row = byT.get(p.t) ?? { x: axis === 'uptime' ? p.t : wall, t: p.t, wall }
      row[s.resource] = p.avg
      byT.set(p.t, row)
    }
  }
  return [...byT.values()].sort((a, b) => a.t - b.t)
}

/** The most expensive single block entities and entities of the last measurement. */
function TopList() {
  const t = useT()
  const { locale } = usePreferences()
  const top = useQuery({
    queryKey: ['details', TOTAL],
    queryFn: () => call<{ rows: Record<string, string>[] }>(api.GET('/api/v1/details', { params: { query: { resource: TOTAL } } })),
    refetchInterval: 5_000,
  })
  const rows = top.data?.rows ?? []
  return (
    <Card className="min-w-0 gap-0 py-0" data-testid="profiler-top">
      <div className="text-muted-foreground grid grid-cols-[minmax(0,1fr)_5rem] gap-3 border-b px-4 py-2 text-xs font-medium">
        <span>{t('prof.topTitle')}</span>
        <span className="text-right">{t('prof.perTick')}</span>
      </div>
      <ul className="divide-border max-h-[22rem] min-w-0 divide-y overflow-y-auto">
        {rows.slice(0, 25).map((row) => (
          <li key={`${row.kind}:${row.where}`} className="grid min-w-0 grid-cols-[minmax(0,1fr)_5rem] items-center gap-3 px-4 py-1.5">
            <div className="min-w-0">
              <p className="truncate text-sm">
                <McText text={row.name || row.type} />
              </p>
              <p className="text-muted-foreground truncate text-xs">
                {row.where.replace(/^minecraft:/, '').replace('@', ' · ').split(',').join(', ')}
              </p>
            </div>
            <span className="tabular text-right text-sm font-medium">{ms(Number(row.us), locale)}</span>
          </li>
        ))}
        {rows.length === 0 && <li className="text-muted-foreground px-4 py-3 text-sm">{t('prof.noTop')}</li>}
      </ul>
    </Card>
  )
}
