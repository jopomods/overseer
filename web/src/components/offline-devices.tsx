import { useState } from 'react'
import { ItemIcon } from '@/components/item-icon'
import { McText } from '@/components/mc-text'
import { Section } from '@/components/stat'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { idPos, type Ae2Offline } from '@/lib/ae2'
import { useT } from '@/lib/i18n'

const SHOWN = 12

/**
 * Devices of an ME network that need a channel and have none (AE2 shows them dark), checked every
 * 10 s. Only shown when there are some.
 */
export function OfflineDevicesSection({ devices }: { devices: Ae2Offline[] }) {
  const t = useT()
  const [all, setAll] = useState(false)
  if (devices.length === 0) return null
  const shown = all ? devices : devices.slice(0, SHOWN)
  return (
    <Section
      title={t('offline.title')}
      aside={<span className="text-warning shrink-0 text-xs font-medium">{t('offline.count', { count: devices.length })}</span>}
    >
      <Card className="border-warning/50 min-w-0 gap-0 py-1" data-testid="offline-devices">
        <p className="text-muted-foreground px-4 py-2 text-sm">{t('offline.hint')}</p>
        <ul className="divide-border border-border grid min-w-0 divide-y border-t sm:grid-cols-2 sm:divide-y-0">
          {shown.map((d) => (
            <li key={d.id} className="flex min-w-0 items-center gap-2 px-4 py-2">
              <ItemIcon stackKey={d.item || 'item:ae2:fluix_glass_cable'} name={d.name || '?'} />
              <span className="min-w-0 flex-1 truncate text-sm">
                <McText text={d.name || d.item} />
              </span>
              <span className="text-muted-foreground tabular shrink-0 text-xs">
                {idPos(d.side ? d.id.slice(0, d.id.lastIndexOf('/')) : d.id)}
                {d.side && ` · ${t(`provider.side.${d.side}` as 'provider.side.north')}`}
              </span>
            </li>
          ))}
        </ul>
        {devices.length > SHOWN && (
          <Button variant="ghost" size="sm" className="m-2 self-start" onClick={() => setAll((a) => !a)}>
            {all ? t('cpu.fewer') : t('cpu.allLines', { count: devices.length })}
          </Button>
        )}
      </Card>
    </Section>
  )
}
