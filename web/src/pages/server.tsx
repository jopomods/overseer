import { useCollectors, useSnapshot } from '@/api/hooks'
import { useSession } from '@/components/session'
import { HistoryChart } from '@/components/history-chart'
import { LiveBadge } from '@/components/live-badge'
import { PageHeader, Section, Stat } from '@/components/stat'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { Progress } from '@/components/ui/progress'
import { formatBytes, formatNanos, formatNumber } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { usePreferences } from '@/lib/preferences'
import { find, metric, ofType } from '@/lib/snapshot'
import { tpsTone } from '@/lib/tone'
import { ProfilerSection } from '@/components/profiler-section'

export function ServerPage() {
  const t = useT()
  const { locale } = usePreferences()
  const { me, live } = useSession()
  const isAdmin = me.role !== 'player'
  const snapshot = useSnapshot('server', { pollMs: live === 'live' ? false : 5000 }).data

  const main = find(snapshot, 'server', 'main')
  const tps = metric(main, 'tps')
  const players = ofType(snapshot, 'player').sort((a, b) =>
    (a.attributes.name ?? '').localeCompare(b.attributes.name ?? ''),
  )
  const dimensions = ofType(snapshot, 'dimension').sort(
    (a, b) => (metric(b, 'loaded_chunks') ?? 0) - (metric(a, 'loaded_chunks') ?? 0),
  )
  const jvm = find(snapshot, 'jvm', 'main')

  return (
    <>
      <PageHeader title={t('server.title')}>
        <LiveBadge state={live} dataAt={snapshot?.at.wall} />
      </PageHeader>

      <div className="grid min-w-0 grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label={t('server.tps')} value={tps === undefined ? '–' : formatNumber(tps, locale, 1)} tone={tpsTone(tps)} />
        <Stat label={t('server.mspt')} value={formatNumber(metric(main, 'mspt') ?? NaN, locale, 1)} hint="ms" />
        <Stat
          label={t('server.playersOnline')}
          value={`${metric(main, 'players_online') ?? '–'}`}
          hint={main?.attributes.maxPlayers ? `max ${main.attributes.maxPlayers}` : undefined}
        />
        <Stat label="Version" value={main?.attributes.version ?? '–'} hint={main?.attributes.motd} />
      </div>

      <Section title={t('chart.performance')}>
        <div className="grid min-w-0 gap-3 xl:grid-cols-2">
          <HistoryChart
            testId="chart-tps"
            resource="server/server/main"
            series={[{ metric: 'tps', label: t('server.tps'), color: 'var(--success)' }]}
            format={(v) => formatNumber(v, locale, 1)}
          />
          <HistoryChart
            resource="server/server/main"
            series={[
              { metric: 'mspt', label: t('server.mspt'), color: 'var(--primary)' },
              { metric: 'players_online', label: t('server.playersOnline'), color: 'var(--chart-5)' },
            ]}
            format={(v) => formatNumber(v, locale, 1)}
          />
        </div>
      </Section>

      <Section title={t('server.players')}>
        {players.length === 0 ? (
          <p className="text-muted-foreground text-sm">{t('server.noPlayers')}</p>
        ) : (
          <Card className="min-w-0 py-2">
            <ul className="divide-border divide-y" data-testid="player-list">
              {players.map((p) => (
                <li key={p.ref.key} className="flex min-w-0 items-center gap-3 px-4 py-2.5">
                  <span className="bg-primary/15 text-primary flex size-8 shrink-0 items-center justify-center rounded text-sm font-bold">
                    {(p.attributes.name ?? '?').slice(0, 1).toUpperCase()}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">{p.attributes.name}</span>
                    <span className="text-muted-foreground block truncate text-xs">{p.attributes.dimension}</span>
                  </span>
                  <span className="text-muted-foreground tabular shrink-0 text-xs">
                    {formatNumber(metric(p, 'ping') ?? NaN, locale)} ms
                  </span>
                </li>
              ))}
            </ul>
          </Card>
        )}
      </Section>

      {isAdmin && (
        <>
          <ProfilerSection />
          <Section title={t('server.dimensions')} aside={<Badge variant="secondary">{t('common.adminOnly')}</Badge>}>
            <div className="grid min-w-0 gap-3 sm:grid-cols-2 xl:grid-cols-3">
              {dimensions.map((d) => (
                <Card key={d.ref.key} className="min-w-0 gap-2 py-4">
                  <CardContent className="min-w-0 px-4">
                    <p className="truncate text-sm font-medium" title={d.ref.id}>
                      {d.ref.id}
                    </p>
                    <dl className="tabular mt-2 grid grid-cols-3 gap-2 text-xs">
                      <Figure label={t('server.chunks')} value={formatNumber(metric(d, 'loaded_chunks') ?? NaN, locale)} />
                      <Figure label={t('server.entities')} value={formatNumber(metric(d, 'entities') ?? NaN, locale)} />
                      <Figure label={t('server.mspt')} value={formatNumber(metric(d, 'dimension_mspt') ?? NaN, locale, 1)} />
                    </dl>
                  </CardContent>
                </Card>
              ))}
            </div>
          </Section>

          {jvm && <MemoryCard used={metric(jvm, 'memory_used') ?? 0} max={metric(jvm, 'memory_max') ?? 0} />}
          <CollectorsSection />
        </>
      )}
    </>
  )
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-muted-foreground truncate">{label}</dt>
      <dd className="truncate text-sm font-semibold">{value}</dd>
    </div>
  )
}

function MemoryCard({ used, max }: { used: number; max: number }) {
  const t = useT()
  const { locale } = usePreferences()
  const percent = max > 0 ? (used / max) * 100 : 0
  return (
    <Section title={t('server.memory')}>
      <Card className="min-w-0 py-4">
        <CardContent className="min-w-0 space-y-2 px-4">
          <div className="tabular flex min-w-0 justify-between gap-3 text-sm">
            <span className="truncate font-medium">
              {formatBytes(used, locale)} / {formatBytes(max, locale)}
            </span>
            <span className="text-muted-foreground shrink-0">{formatNumber(percent, locale)} %</span>
          </div>
          <Progress value={percent} />
        </CardContent>
      </Card>
    </Section>
  )
}

function CollectorsSection() {
  const t = useT()
  const { locale } = usePreferences()
  const data = useCollectors(true).data
  if (!data) return null
  return (
    <Section title={t('server.collectors')}>
      <div className="grid min-w-0 gap-3 sm:grid-cols-2" data-testid="collectors">
        {data.collectors.map((c) => (
          <Card key={c.provider} className="min-w-0 gap-2 py-4">
            <CardContent className="min-w-0 space-y-1 px-4">
              <div className="flex min-w-0 items-center justify-between gap-2">
                <span className="truncate text-sm font-medium">{c.provider}</span>
                {c.throttled && <Badge variant="destructive">{t('server.throttled')}</Badge>}
              </div>
              <p className="tabular text-muted-foreground truncate text-xs">
                {t('server.avg')} {formatNanos(c.avgNanos, locale)} · {t('server.max')} {formatNanos(c.maxNanos, locale)} ·{' '}
                {t('server.every', { ticks: c.currentIntervalTicks })}
              </p>
              {c.errors > 0 && (
                <p className="text-destructive truncate text-xs" title={c.lastError}>
                  {t('server.errors', { count: c.errors })}: {c.lastError}
                </p>
              )}
            </CardContent>
          </Card>
        ))}
      </div>
    </Section>
  )
}
