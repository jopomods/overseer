import { ArrowDownToLine, ArrowUpFromLine, Search } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Segmented } from '@/components/history-chart'
import { ItemIcon } from '@/components/item-icon'
import { McText } from '@/components/mc-text'
import { Section } from '@/components/stat'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { idPos, type Ae2Bus } from '@/lib/ae2'
import { useT, type MessageKey } from '@/lib/i18n'
import { plainText } from '@/lib/mc-text'
import { cn } from '@/lib/utils'

const SHOWN = 12
const FILTER_SHOWN = 9

type Filter = 'all' | 'import' | 'export'

/**
 * Import and export buses of an ME network: what they face, what they move (filter), their cards
 * and settings. Search covers names, targets and filter items.
 */
export function BusesSection({ buses, extra }: { buses: Ae2Bus[]; extra?: (bus: Ae2Bus) => ReactNode }) {
  const t = useT()
  const [kind, setKind] = useState<Filter>('all')
  const [query, setQuery] = useState('')
  const [all, setAll] = useState(false)
  if (buses.length === 0) return null
  const q = query.trim().toLowerCase()
  const list = buses.filter((b) => {
    if (kind !== 'all' && b.kind !== kind) return false
    if (!q) return true
    const text = `${plainText(b.name ?? '')} ${plainText(b.target)} ${b.targetBlock} ${b.filter.map((f) => plainText(f.name)).join(' ')}`
    return text.toLowerCase().includes(q)
  })
  const imports = buses.filter((b) => b.kind === 'import').length
  const exports = buses.filter((b) => b.kind === 'export').length
  const shown = all || q ? list : list.slice(0, SHOWN)

  return (
    <Section
      title={t('bus.title')}
      aside={<span className="text-muted-foreground shrink-0 text-xs">{t('bus.summary', { imports, exports })}</span>}
    >
      <div className="mb-3 flex min-w-0 flex-wrap items-center gap-2" data-testid="bus-toolbar">
        <Segmented<Filter>
          value={kind}
          onChange={setKind}
          options={[
            { value: 'all', label: t('bus.all') },
            { value: 'import', label: t('bus.import') },
            { value: 'export', label: t('bus.export') },
          ]}
        />
        <div className="relative min-w-0 flex-1 basis-48 sm:max-w-80">
          <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2" />
          <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t('bus.search')} aria-label={t('bus.search')} className="pl-8" />
        </div>
      </div>
      {list.length === 0 && <p className="text-muted-foreground text-sm">{t('bus.none')}</p>}
      <ul className="grid min-w-0 items-start gap-3 md:grid-cols-2 xl:grid-cols-3" data-testid="bus-list">
        {shown.map((b) => (
          <BusCard key={b.id} bus={b} extra={extra?.(b)} />
        ))}
      </ul>
      {!q && list.length > SHOWN && (
        <Button variant="ghost" size="sm" className="mt-2" onClick={() => setAll((a) => !a)}>
          {all ? t('cpu.fewer') : t('cpu.allLines', { count: list.length })}
        </Button>
      )}
    </Section>
  )
}

/** "speed_card" -> "Speed", for the card badges; unknown cards keep their path. */
function cardName(item: string, t: ReturnType<typeof useT>): string {
  const path = item.slice(item.indexOf(':') + 1)
  const known = ['speed_card', 'capacity_card', 'fuzzy_card', 'crafting_card', 'redstone_card', 'inverter_card', 'void_card']
  return known.includes(path) ? t(`bus.card.${path}` as MessageKey) : path.replace(/_/g, ' ')
}

function BusCard({ bus: b, extra }: { bus: Ae2Bus; extra?: ReactNode }) {
  const t = useT()
  const Direction = b.kind === 'export' ? ArrowUpFromLine : ArrowDownToLine
  const label = b.name ?? b.target
  return (
    <li className="min-w-0">
      <Card className={cn('min-w-0 gap-2 px-4 py-3', !b.active && 'border-warning/60')}>
        <div className="flex min-w-0 items-center gap-2">
          <Direction
            className={cn('size-4 shrink-0', b.kind === 'export' ? 'text-chart-1' : 'text-success')}
            aria-label={b.kind === 'export' ? t('bus.export') : b.kind === 'import' ? t('bus.import') : b.kind}
          />
          <ItemIcon stackKey={b.targetBlock ? `item:${b.targetBlock}` : `item:${b.block}`} name={plainText(label) || '?'} />
          <span className="min-w-0 flex-1 truncate text-sm font-medium">{label ? <McText text={label} /> : t('bus.nothing')}</span>
          {!b.active && <Badge variant="outline" className="text-warning border-warning/60">{t('bus.inactive')}</Badge>}
        </div>
        <p className="text-muted-foreground min-w-0 truncate text-xs">
          {b.kind === 'export' ? t('bus.export') : b.kind === 'import' ? t('bus.import') : b.kind} · {idPos(b.side ? b.id.slice(0, b.id.lastIndexOf('/')) : b.id)}
          {b.name && b.target && <> · {plainText(b.target)}</>}
        </p>
        <div className="flex min-w-0 flex-wrap items-center gap-1" aria-label={t('bus.filter')} data-testid="bus-filter">
          {b.filter.length === 0 ? (
            <span className="text-muted-foreground text-xs">{b.kind === 'export' ? t('bus.noFilterExport') : t('bus.everything')}</span>
          ) : (
            <>
              {b.filter.slice(0, FILTER_SHOWN).map((f, i) => (
                <span key={`${f.key}-${i}`} title={plainText(f.name)}>
                  <ItemIcon stackKey={f.key} name={f.name} className="size-5 [&>img]:size-5" />
                </span>
              ))}
              {b.filter.length > FILTER_SHOWN && <span className="text-muted-foreground text-xs">+{b.filter.length - FILTER_SHOWN}</span>}
            </>
          )}
        </div>
        {(b.upgrades.length > 0 || b.redstone !== 'ignore' || b.craftOnly || b.requested > 0 || (b.scheduling && b.scheduling !== 'default')) && (
          <div className="flex min-w-0 flex-wrap gap-1">
            {b.upgrades.map((u) => (
              <Badge key={u.item} variant="secondary">
                {cardName(u.item, t)}
                {u.count > 1 && ` ×${u.count}`}
              </Badge>
            ))}
            {b.redstone !== 'ignore' && <Badge variant="outline">{t(`bus.redstone.${b.redstone}` as MessageKey)}</Badge>}
            {b.craftOnly && <Badge variant="outline">{t('bus.craftOnly')}</Badge>}
            {b.scheduling && b.scheduling !== 'default' && <Badge variant="outline">{t(`bus.scheduling.${b.scheduling}` as MessageKey)}</Badge>}
            {b.requested > 0 && <Badge variant="outline">{t('bus.requested', { count: b.requested })}</Badge>}
          </div>
        )}
        {extra}
      </Card>
    </li>
  )
}
