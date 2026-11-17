import { Activity, Server, Zap } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from '@tanstack/react-router'
import { useCollectors, useProviders, useSnapshot } from '@/api/hooks'
import { useSession } from '@/components/session'
import { LiveBadge } from '@/components/live-badge'
import { PageHeader } from '@/components/stat'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { formatNumber, formatRate } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { usePreferences } from '@/lib/preferences'
import { find, metric, ofType } from '@/lib/snapshot'
import { tpsTone } from '@/lib/tone'

/** Landing page: one tile per active module plus server health. */
export function OverviewPage() {
  const t = useT()
  const { me, live } = useSession()
  const providers = useProviders()
  const poll = live === 'live' ? false : 5000
  const server = useSnapshot('server', { pollMs: poll })
  const hasFlux = providers.data?.some((p) => p.id === 'flux') ?? false

  return (
    <>
      <PageHeader title={t('overview.title')}>
        <LiveBadge state={live} dataAt={server.data?.at.wall} />
      </PageHeader>
      <div className="grid min-w-0 gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <ServerTile />
        {hasFlux && <FluxTile poll={poll} />}
        {me.role !== 'player' && <HealthTile />}
      </div>
    </>
  )
}

function Tile({ icon, title, to, children }: { icon: ReactNode; title: string; to?: '/server' | '/energy'; children: ReactNode }) {
  const card = (
    <Card className="h-full min-w-0 gap-3 transition-colors hover:border-primary/40">
      <CardHeader className="flex min-w-0 flex-row items-center gap-2">
        <span className="text-primary">{icon}</span>
        <CardTitle className="min-w-0 truncate">{title}</CardTitle>
      </CardHeader>
      <CardContent className="min-w-0">{children}</CardContent>
    </Card>
  )
  return to ? (
    <Link to={to} className="min-w-0 rounded-xl focus-visible:outline-2">
      {card}
    </Link>
  ) : (
    card
  )
}

function Figure({ label, value, tone }: { label: string; value: string; tone?: 'good' | 'warn' | 'bad' }) {
  return (
    <div className="min-w-0">
      <p className="text-muted-foreground truncate text-xs">{label}</p>
      <p
        className={
          'tabular truncate text-xl font-semibold ' +
          (tone === 'good' ? 'text-success' : tone === 'warn' ? 'text-warning' : tone === 'bad' ? 'text-destructive' : '')
        }
      >
        {value}
      </p>
    </div>
  )
}

function ServerTile() {
  const t = useT()
  const { locale } = usePreferences()
  const snapshot = useSnapshot('server').data
  const main = find(snapshot, 'server', 'main')
  const tps = metric(main, 'tps')
  const players = metric(main, 'players_online')
  return (
    <Tile icon={<Server className="size-4" />} title={t('overview.server')} to="/server">
      <div className="grid grid-cols-3 gap-3">
        <Figure label={t('server.tps')} value={tps === undefined ? '–' : formatNumber(tps, locale, 1)} tone={tpsTone(tps)} />
        <Figure label={t('server.mspt')} value={formatNumber(metric(main, 'mspt') ?? NaN, locale, 1)} />
        <Figure
          label={t('server.players')}
          value={players === undefined ? '–' : `${players}/${main?.attributes.maxPlayers ?? '?'}`}
        />
      </div>
    </Tile>
  )
}

function FluxTile({ poll }: { poll: number | false }) {
  const t = useT()
  const { locale, energyUnit } = usePreferences()
  const networks = ofType(useSnapshot('flux', { pollMs: poll }).data, 'network')
  const input = networks.reduce((sum, n) => sum + (metric(n, 'energy_input') ?? 0), 0)
  const output = networks.reduce((sum, n) => sum + (metric(n, 'energy_output') ?? 0), 0)
  return (
    <Tile icon={<Zap className="size-4" />} title={t('overview.energy')} to="/energy">
      {networks.length === 0 ? (
        <p className="text-muted-foreground text-sm">{t('overview.noNetworks')}</p>
      ) : (
        <div className="grid grid-cols-2 gap-3">
          <Figure label={t('overview.input')} value={formatRate(input, energyUnit, locale)} />
          <Figure label={t('overview.output')} value={formatRate(output, energyUnit, locale)} />
          <p className="text-muted-foreground col-span-2 truncate text-xs">
            {t('overview.networks', { count: networks.length })}
          </p>
        </div>
      )}
    </Tile>
  )
}

function HealthTile() {
  const t = useT()
  const collectors = useCollectors(true).data
  const throttled = collectors?.collectors.filter((c) => c.throttled).length ?? 0
  return (
    <Tile icon={<Activity className="size-4" />} title={t('overview.health')} to="/server">
      <p className={'text-sm ' + (throttled ? 'text-warning' : 'text-muted-foreground')}>
        {throttled ? t('overview.healthThrottled', { count: throttled }) : t('overview.healthOk')}
      </p>
    </Tile>
  )
}
