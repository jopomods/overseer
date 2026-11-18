import { Link } from '@tanstack/react-router'
import { Cpu, Package } from 'lucide-react'
import { useOverrides, useSnapshot } from '@/api/hooks'
import { GridStateBadge } from '@/components/grid-state-badge'
import { LiveBadge } from '@/components/live-badge'
import { useSession } from '@/components/session'
import { PageHeader } from '@/components/stat'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatAe, formatAeRate, formatPos, grids, type Ae2Grid } from '@/lib/ae2'
import { formatSi } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { usePreferences } from '@/lib/preferences'

/** All AE2 networks the viewer may see, as cards. */
export function Ae2Page() {
  const t = useT()
  const { live } = useSession()
  const snapshot = useSnapshot('ae2', { pollMs: live === 'live' ? false : 5000 })
  const overrides = useOverrides('ae2').data
  const list = grids(snapshot.data, overrides)

  return (
    <>
      <PageHeader title={t('ae2.title')}>
        <LiveBadge state={live} dataAt={snapshot.data?.at.wall} />
      </PageHeader>
      {snapshot.isPending ? (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-44" />
          ))}
        </div>
      ) : list.length === 0 ? (
        <p className="text-muted-foreground max-w-prose text-sm">{t('ae2.empty')}</p>
      ) : (
        <div className="grid min-w-0 gap-4 sm:grid-cols-2 xl:grid-cols-3" data-testid="grid-list">
          {list.map((g) => (
            <GridCard key={g.id} grid={g} />
          ))}
        </div>
      )}
    </>
  )
}

function GridCard({ grid: g }: { grid: Ae2Grid }) {
  const t = useT()
  const { locale } = usePreferences()
  const fill = g.powerMax > 0 ? Math.min(1, g.powerStored / g.powerMax) : 0
  return (
    <Link to="/ae2/$gridId" params={{ gridId: g.id }} className="min-w-0 rounded-xl focus-visible:outline-2">
      <Card className="hover:border-primary/40 h-full min-w-0 gap-4 py-4 transition-colors">
        <CardContent className="min-w-0 space-y-4 px-4">
          <div className="flex min-w-0 items-start gap-2.5">
            <div className="min-w-0 flex-1">
              <p className="truncate font-semibold">{g.name ?? t('ae2.unnamed', { pos: formatPos(g.pos) })}</p>
              <p className="text-muted-foreground truncate text-xs">{g.ownerName || t('common.unknown')}</p>
            </div>
            <GridStateBadge state={g.state} />
          </div>
          <div className="min-w-0 space-y-1.5">
            <div className="tabular flex min-w-0 justify-between gap-3 text-xs">
              <span className="text-muted-foreground truncate">{formatAe(g.powerStored, locale)}</span>
              <span className="shrink-0 font-medium">{formatAeRate(g.usage, locale)}</span>
            </div>
            <div className="bg-muted h-1.5 overflow-hidden rounded-full">
              <div className="bg-chart-5 h-full rounded-full" style={{ width: `${fill * 100}%` }} />
            </div>
          </div>
          <div className="text-muted-foreground tabular flex min-w-0 items-center justify-between gap-3 text-xs">
            <span className="flex min-w-0 items-center gap-1.5">
              <Package className="size-3.5 shrink-0" />
              <span className="truncate">
                {formatSi(g.items, locale)} · {t('ae2.types', { count: formatSi(g.itemTypes, locale) })}
              </span>
            </span>
            {g.cpus > 0 && (
              <span className="flex shrink-0 items-center gap-1.5">
                <Cpu className="size-3.5" />
                {g.cpusBusy}/{g.cpus}
              </span>
            )}
          </div>
        </CardContent>
      </Card>
    </Link>
  )
}
