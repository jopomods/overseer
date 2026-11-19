import { Link } from '@tanstack/react-router'
import { Search } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useInventorySearch } from '@/api/hooks'
import { ItemIcon } from '@/components/item-icon'
import { McText } from '@/components/mc-text'
import { Section } from '@/components/stat'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { formatBuckets, formatPos, type Ae2Grid } from '@/lib/ae2'
import { formatSi } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { usePreferences, type Locale } from '@/lib/preferences'

const PREFIX = 'ae2/grid/'

/**
 * Finds an item in every ME network the viewer may see (newest scans, every 10 s): the total and
 * which networks hold it, each linking to its page.
 */
export function ItemSearchSection({ grids }: { grids: Ae2Grid[] }) {
  const t = useT()
  const { locale } = usePreferences()
  const [query, setQuery] = useState('')
  const [q, setQ] = useState('')
  useEffect(() => {
    const timer = setTimeout(() => setQ(query.trim()), 300)
    return () => clearTimeout(timer)
  }, [query])
  const search = useInventorySearch(q)
  const byKey = new Map(grids.map((g) => [g.resource.ref.key, g]))
  const items = q.length >= 2 ? (search.data?.items ?? []) : []

  return (
    <Section title={t('search.title')}>
      <div className="relative mb-3 min-w-0 sm:max-w-96">
        <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2" />
        <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t('search.placeholder')} aria-label={t('search.placeholder')} className="pl-8" />
      </div>
      {q.length >= 2 && search.data && items.length === 0 && <p className="text-muted-foreground text-sm">{t('search.none')}</p>}
      {items.length > 0 && (
        <ul className="grid min-w-0 items-start gap-3 md:grid-cols-2 xl:grid-cols-3" data-testid="item-search">
          {items.map((item) => (
            <li key={item.key} className="min-w-0">
              <Card className="min-w-0 gap-2 px-4 py-3">
                <div className="flex min-w-0 items-center gap-2">
                  <ItemIcon stackKey={item.key} name={item.name} />
                  <span className="min-w-0 flex-1 truncate text-sm font-medium">
                    <McText text={item.name} />
                  </span>
                  <span className="tabular shrink-0 text-sm font-semibold">{amount(item.total, item.kind, locale)}</span>
                </div>
                <ul className="min-w-0 space-y-1 text-xs">
                  {item.places.map((place) => {
                    const grid = byKey.get(place.resource)
                    const label = place.name ?? grid?.name ?? t('ae2.unnamed', { pos: grid ? formatPos(grid.pos) : place.resource })
                    return (
                      <li key={place.resource} className="flex min-w-0 items-center justify-between gap-2">
                        {place.resource.startsWith(PREFIX) ? (
                          <Link
                            to="/ae2/$gridId"
                            params={{ gridId: place.resource.slice(PREFIX.length) }}
                            className="min-w-0 truncate underline-offset-2 hover:underline"
                          >
                            <McText text={label} />
                          </Link>
                        ) : (
                          <span className="min-w-0 truncate">
                            <McText text={label} />
                          </span>
                        )}
                        <span className="text-muted-foreground tabular shrink-0">{amount(place.amount, item.kind, locale)}</span>
                      </li>
                    )
                  })}
                  {item.placeCount > item.places.length && (
                    <li className="text-muted-foreground">{t('search.morePlaces', { count: item.placeCount - item.places.length })}</li>
                  )}
                </ul>
              </Card>
            </li>
          ))}
        </ul>
      )}
      {search.data && search.data.total > items.length && items.length > 0 && (
        <p className="text-muted-foreground mt-2 text-xs">{t('search.moreItems', { count: search.data.total - items.length })}</p>
      )}
    </Section>
  )
}

function amount(value: number, kind: string, locale: Locale) {
  return kind === 'fluid' ? `${formatBuckets(value, locale)} B` : formatSi(value, locale)
}
