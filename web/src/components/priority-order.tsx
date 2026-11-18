import { BatteryCharging, Cable, Plug, Zap } from 'lucide-react'
import { McText } from '@/components/mc-text'
import { TierBadge } from '@/components/tier-badge'
import { Card } from '@/components/ui/card'
import { priorityOrder, type FluxDevice, type PriorityGroup } from '@/lib/flux'
import { formatRate } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { usePreferences } from '@/lib/preferences'
import { cn } from '@/lib/utils'

const SHOWN_PER_GROUP = 5

/**
 * The order in which Flux serves devices: who gets energy first and whose energy is used first.
 * Same priority = they share; power surge goes before everything.
 */
export function PriorityOrder({ devices }: { devices: FluxDevice[] }) {
  const t = useT()
  const receive = priorityOrder(devices, 'receive')
  const supply = priorityOrder(devices, 'supply')
  if (receive.length + supply.length === 0) return null
  return (
    <div className="min-w-0 space-y-3" data-testid="priority-order">
      <p className="text-muted-foreground text-sm">{t('priority.explain')}</p>
      <div className="grid min-w-0 gap-3 lg:grid-cols-2">
        <Column title={t('priority.receive')} groups={receive} testId="priority-receive" />
        <Column title={t('priority.supply')} groups={supply} testId="priority-supply" />
      </div>
    </div>
  )
}

function Column({ title, groups, testId }: { title: string; groups: PriorityGroup[]; testId: string }) {
  const t = useT()
  const { locale, energyUnit } = usePreferences()
  return (
    <Card className="min-w-0 gap-0 py-1" data-testid={testId}>
      <p className="text-muted-foreground px-4 pt-2 pb-1 text-xs font-medium tracking-wide uppercase">{title}</p>
      {groups.length === 0 && <p className="text-muted-foreground px-4 py-2 text-sm">{t('energy.noDevices')}</p>}
      <ol className="divide-border divide-y">
        {groups.map((g, rank) => (
          <li key={String(g.priority)} className="min-w-0 px-4 py-2">
            <div className="flex min-w-0 items-center gap-2 text-sm">
              <span className="bg-muted text-muted-foreground tabular flex size-5 shrink-0 items-center justify-center rounded text-xs">
                {rank + 1}
              </span>
              <span className="min-w-0 flex-1 truncate font-medium">
                {g.priority === 'surge' ? (
                  <span className="text-warning inline-flex items-center gap-1">
                    <Zap className="size-3.5" /> {t('edit.surge')}
                  </span>
                ) : (
                  `${t('energy.priority')} ${g.priority}`
                )}
              </span>
              <span className="text-muted-foreground shrink-0 text-xs">
                {g.devices.length > 1 ? t('priority.share', { count: g.devices.length }) : t('priority.alone')}
              </span>
              <span className="tabular shrink-0 text-xs font-semibold">{formatRate(g.transfer, energyUnit, locale)}</span>
            </div>
            <ul className="mt-1 space-y-0.5 pl-7">
              {g.devices.slice(0, SHOWN_PER_GROUP).map((d) => (
                <li key={d.id} className={cn('flex min-w-0 items-center gap-1.5 text-xs', !d.loaded && 'opacity-60')}>
                  {d.type === 'storage' ? (
                    <BatteryCharging className="text-muted-foreground size-3 shrink-0" />
                  ) : d.type === 'plug' ? (
                    <Plug className="text-success size-3 shrink-0" />
                  ) : (
                    <Cable className="text-primary size-3 shrink-0" />
                  )}
                  <span className="min-w-0 truncate">
                    {d.zone ? `${d.zone}: ` : ''}
                    <McText text={d.title} />
                  </span>
                  {d.tier && <TierBadge tier={d.tier} />}
                  <span className="text-muted-foreground tabular ml-auto shrink-0">
                    {formatRate(Math.abs(d.transfer), energyUnit, locale)}
                  </span>
                </li>
              ))}
              {g.devices.length > SHOWN_PER_GROUP && (
                <li className="text-muted-foreground text-xs">
                  {t('priority.more', { count: g.devices.length - SHOWN_PER_GROUP })}
                </li>
              )}
            </ul>
          </li>
        ))}
      </ol>
    </Card>
  )
}
