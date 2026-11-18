import { Link } from '@tanstack/react-router'
import type { ReactNode } from 'react'
import { ArrowDownToLine, ArrowUpFromLine, Battery } from 'lucide-react'
import { useSnapshot } from '@/api/hooks'
import { LiveBadge } from '@/components/live-badge'
import { McText } from '@/components/mc-text'
import { useSession } from '@/components/session'
import { SecurityBadge } from '@/components/security-badge'
import { PageHeader } from '@/components/stat'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { devices, networks, type FluxNetwork } from '@/lib/flux'
import { formatEnergy, formatRate } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { usePreferences } from '@/lib/preferences'

/** All Flux networks the viewer may see, as cards. */
export function EnergyPage() {
  const t = useT()
  const { live } = useSession()
  const snapshot = useSnapshot('flux', { pollMs: live === 'live' ? false : 5000 })
  const list = networks(snapshot.data)

  return (
    <>
      <PageHeader title={t('energy.title')}>
        <LiveBadge state={live} dataAt={snapshot.data?.at.wall} />
      </PageHeader>
      {snapshot.isPending ? (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-40" />
          ))}
        </div>
      ) : list.length === 0 ? (
        <p className="text-muted-foreground max-w-prose text-sm">{t('energy.empty')}</p>
      ) : (
        <div className="grid min-w-0 gap-4 sm:grid-cols-2 xl:grid-cols-3" data-testid="network-list">
          {list.map((n) => (
            <NetworkCard key={n.id} network={n} deviceCount={devices(snapshot.data, n.id).length} />
          ))}
        </div>
      )}
    </>
  )
}

function NetworkCard({ network: n, deviceCount }: { network: FluxNetwork; deviceCount: number }) {
  const t = useT()
  const { locale, energyUnit } = usePreferences()
  return (
    <Link to="/energy/$networkId" params={{ networkId: n.id }} className="min-w-0 rounded-xl focus-visible:outline-2">
      <Card className="hover:border-primary/40 h-full min-w-0 gap-4 py-4 transition-colors">
        <CardContent className="min-w-0 space-y-4 px-4">
          <div className="flex min-w-0 items-center gap-2.5">
            <span className="size-3 shrink-0 rounded-full" style={{ backgroundColor: n.color }} />
            <span className="min-w-0 flex-1 truncate font-semibold">
              <McText text={n.name} />
            </span>
            <SecurityBadge security={n.security} />
          </div>
          <div className="tabular grid grid-cols-2 gap-3">
            <Flow icon={<ArrowDownToLine className="text-success size-3.5" />} label={t('energy.input')} value={formatRate(n.input, energyUnit, locale)} />
            <Flow icon={<ArrowUpFromLine className="text-primary size-3.5" />} label={t('energy.output')} value={formatRate(n.output, energyUnit, locale)} />
          </div>
          <div className="text-muted-foreground flex min-w-0 items-center justify-between gap-3 text-xs">
            <span className="flex min-w-0 items-center gap-1.5">
              <Battery className="size-3.5 shrink-0" />
              <span className="truncate">{formatEnergy(n.energy, energyUnit, locale)}</span>
            </span>
            <span className="shrink-0">{t('energy.devices', { count: deviceCount })}</span>
          </div>
        </CardContent>
      </Card>
    </Link>
  )
}

function Flow({ icon, label, value }: { icon: ReactNode; label: string; value: string }) {
  return (
    <div className="min-w-0">
      <p className="text-muted-foreground flex items-center gap-1 text-xs">
        {icon}
        <span className="truncate">{label}</span>
      </p>
      <p className="truncate text-lg font-semibold">{value}</p>
    </div>
  )
}
