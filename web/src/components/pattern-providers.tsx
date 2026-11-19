import { ChevronDown, Lock, Pencil, Search } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { useDetails, useRunAction } from '@/api/hooks'
import { CraftButton } from '@/components/craft'
import { Actions, Counter, Feedback, Field } from '@/components/edit-form'
import { Segmented } from '@/components/history-chart'
import { ItemIcon } from '@/components/item-icon'
import { McText } from '@/components/mc-text'
import { Section } from '@/components/stat'
import { TierBadge } from '@/components/tier-badge'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import {
  AE2_ACTIONS,
  LOCK_MODES,
  MAX_AE2_NAME,
  formatBuckets,
  idPos,
  patternsOf,
  providerLabel,
  type Ae2Pattern,
  type Ae2PatternProvider,
  type LockMode,
  type PatternStack,
} from '@/lib/ae2'
import { errorText, SELECT } from '@/lib/edit'
import { formatNumber } from '@/lib/format'
import { useT, type Translate } from '@/lib/i18n'
import { plainText } from '@/lib/mc-text'
import { usePreferences, type Locale } from '@/lib/preferences'
import { tierOfMachine, withTier, type Tier } from '@/lib/tiers'
import { cn } from '@/lib/utils'

const SHOWN = 12
const ITEM_ID = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/

/**
 * Pattern providers of an ME network (ADR-0017): what AE2's Pattern Access Terminal shows, their
 * settings and lock state, their patterns. Voltage tiers from the names when the server uses them.
 */
export function PatternProvidersSection({
  grid,
  providers,
  editable,
  tiers,
}: {
  grid: string
  providers: Ae2PatternProvider[]
  editable: boolean
  tiers: Tier[]
}) {
  const t = useT()
  const [query, setQuery] = useState('')
  const [tierFilter, setTierFilter] = useState('all')
  const [all, setAll] = useState(false)
  const details = useDetails(grid, providers.length > 0)
  const patterns = useMemo(() => patternsOf(details.data), [details.data])
  const byProvider = useMemo(() => {
    const map = new Map<string, Ae2Pattern[]>()
    for (const p of patterns) map.set(p.provider, [...(map.get(p.provider) ?? []), p])
    return map
  }, [patterns])
  if (providers.length === 0) return null

  const tierOf = (p: Ae2PatternProvider) => tierOfMachine(providerLabel(p), tiers)
  const usedTiers = tiers.filter((x) => providers.some((p) => tierOf(p) === x))
  const q = query.trim().toLowerCase()
  const list = providers.filter((p) => {
    const tier = tierOf(p)
    if (tierFilter !== 'all' && (tierFilter === 'none' ? tier : tier?.name !== tierFilter)) return false
    if (!q) return true
    const outputs = (byProvider.get(p.id) ?? []).map((x) => plainText(x.output.name)).join(' ')
    return `${plainText(providerLabel(p))} ${p.block} ${outputs}`.toLowerCase().includes(q)
  })
  const locked = providers.filter((p) => p.locked !== 'none').length
  const total = providers.reduce((s, p) => s + p.patterns, 0)
  const shown = all || q ? list : list.slice(0, SHOWN)

  return (
    <Section
      title={t('provider.title')}
      aside={
        <span className="text-muted-foreground shrink-0 text-xs">
          {t('provider.summary', { count: providers.length, patterns: total })}
        </span>
      }
    >
      <div className="mb-3 flex min-w-0 flex-wrap items-center gap-2">
        <div className="relative min-w-0 flex-1 basis-48 sm:max-w-80">
          <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2" />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t('provider.search')}
            aria-label={t('provider.search')}
            className="pl-8"
          />
        </div>
        {locked > 0 && (
          <span className="text-warning flex items-center gap-1 text-xs" data-testid="providers-locked">
            <Lock className="size-3.5" /> {t('provider.lockedCount', { count: locked })}
          </span>
        )}
      </div>
      {usedTiers.length > 0 && (
        <div className="bg-muted mb-3 flex w-fit max-w-full min-w-0 flex-wrap gap-1 rounded-lg p-1" role="tablist" data-testid="provider-tier-filter">
          {['all', ...usedTiers.map((x) => x.name), 'none'].map((key) => {
            const tier = usedTiers.find((x) => x.name === key)
            return (
              <button
                key={key}
                type="button"
                role="tab"
                aria-selected={tierFilter === key}
                onClick={() => setTierFilter(key)}
                className={cn(
                  'flex shrink-0 items-center gap-1 rounded-md px-2 py-1 text-xs font-medium whitespace-nowrap transition-colors',
                  tierFilter === key ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
                )}
              >
                {tier ? <TierBadge tier={tier} /> : key === 'all' ? t('tier.all') : t('tier.none')}
              </button>
            )
          })}
        </div>
      )}
      {list.length === 0 && <p className="text-muted-foreground text-sm">{t('provider.none')}</p>}
      <ul className="grid min-w-0 items-start gap-3 md:grid-cols-2 xl:grid-cols-3" data-testid="provider-list">
        {shown.map((p) => (
          <ProviderCard
            key={p.id}
            grid={grid}
            provider={p}
            tier={tierOf(p)}
            tiers={tiers}
            patterns={byProvider.get(p.id) ?? []}
            editable={editable}
          />
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

/** The icon shown for a provider: the one chosen in the dashboard, else what AE2's terminal shows. */
function providerIcon(p: Ae2PatternProvider): string {
  return p.icon ? `item:${p.icon}` : (p.groupIcon ?? `item:${p.block}`)
}

function ProviderCard({
  grid,
  provider: p,
  tier,
  tiers,
  patterns,
  editable,
}: {
  grid: string
  provider: Ae2PatternProvider
  tier: Tier | undefined
  tiers: Tier[]
  patterns: Ae2Pattern[]
  editable: boolean
}) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState(false)
  const label = providerLabel(p)
  return (
    <li className="min-w-0">
      <Card className={cn('min-w-0 gap-0 py-0', p.locked !== 'none' && 'border-warning/60')}>
        <button
          type="button"
          className="flex min-w-0 flex-col gap-1.5 px-4 py-3 text-left"
          aria-expanded={open}
          onClick={() => setOpen((o) => !o)}
        >
          <div className="flex min-w-0 items-center gap-2">
            <ItemIcon stackKey={providerIcon(p)} name={plainText(label) || '?'} />
            <span className="min-w-0 flex-1 truncate text-sm font-medium">{label ? <McText text={label} /> : t('provider.unnamed')}</span>
            {tier && <TierBadge tier={tier} />}
            <ChevronDown className={cn('text-muted-foreground size-4 shrink-0 transition-transform', open && 'rotate-180')} />
          </div>
          <div className="text-muted-foreground flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs">
            <span className="tabular">{t('provider.patterns', { count: p.patterns })}</span>
            <span className="tabular">
              {t('drive.priority')}: {p.priority}
            </span>
            {p.blocking && <Badge variant="secondary">{t('provider.blocking')}</Badge>}
            {!p.visible && <Badge variant="outline">{t('provider.hidden')}</Badge>}
            {p.locked !== 'none' && (
              <Badge variant="outline" className="text-warning border-warning/60" data-testid="provider-locked">
                <Lock /> {t(`provider.lock.${p.locked}`)}
              </Badge>
            )}
          </div>
        </button>
        {open && (
          <div className="min-w-0 space-y-3 border-t px-4 py-3 text-xs" data-testid="provider-details">
            {editable && !editing && (
              <Button size="sm" variant="ghost" onClick={() => setEditing(true)}>
                <Pencil /> {t('provider.edit')}
              </Button>
            )}
            {editing && <ProviderEditor grid={grid} provider={p} tiers={tiers} patterns={patterns} onDone={() => setEditing(false)} />}
            <dl className="grid min-w-0 grid-cols-2 gap-x-3 gap-y-1">
              <dt className="text-muted-foreground">{t('provider.lockMode')}</dt>
              <dd className="truncate text-right">{t(`provider.lock.${p.lock}`)}</dd>
              <dt className="text-muted-foreground">{t('ae2.location')}</dt>
              <dd className="tabular truncate text-right">
                {idPos(p.side ? p.id.slice(0, p.id.lastIndexOf('/')) : p.id)}
                {p.side && ` · ${t(`provider.side.${p.side}` as 'provider.side.north')}`}
              </dd>
              <dt className="text-muted-foreground">{t('provider.block')}</dt>
              <dd className="truncate text-right" title={p.block}>
                {p.block}
              </dd>
            </dl>
            <PatternList patterns={patterns} />
          </div>
        )}
      </Card>
    </li>
  )
}

function PatternList({ patterns }: { patterns: Ae2Pattern[] }) {
  const t = useT()
  const { locale } = usePreferences()
  const [all, setAll] = useState(false)
  if (patterns.length === 0) return <p className="text-muted-foreground">{t('provider.noPatterns')}</p>
  const shown = all ? patterns : patterns.slice(0, SHOWN)
  return (
    <div className="min-w-0">
      <p className="text-muted-foreground mb-1 font-medium">{t('provider.patternList')}</p>
      <ul className="min-w-0 space-y-1.5" data-testid="pattern-list">
        {shown.map((x, i) => (
          <li key={`${x.output.key}-${i}`} className="min-w-0">
            <div className="flex min-w-0 items-center gap-1.5">
              <ItemIcon stackKey={x.output.key} name={x.output.name} className="size-4 [&>img]:size-4" />
              <span className="min-w-0 flex-1 truncate font-medium">
                <McText text={x.output.name} />
              </span>
              <span className="tabular shrink-0">×{amount(x.output, locale, t)}</span>
              <CraftButton request={{ key: x.output.key, name: x.output.name, kind: x.output.kind }} className="-my-1 shrink-0" />
            </div>
            <div className="text-muted-foreground flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5 pl-5.5">
              {x.inputs.map((input, j) => (
                <span key={j} className="flex min-w-0 items-center gap-1" title={plainText(input.name)}>
                  <ItemIcon stackKey={input.key} name={input.name} className="size-4 [&>img]:size-4" />
                  <span className="tabular">{amount(input, locale, t)}</span>
                </span>
              ))}
            </div>
          </li>
        ))}
      </ul>
      {patterns.length > SHOWN && (
        <Button variant="ghost" size="sm" className="mt-1 h-7 px-2 text-xs" onClick={() => setAll((a) => !a)}>
          {all ? t('cpu.fewer') : t('cpu.allLines', { count: patterns.length })}
        </Button>
      )}
    </div>
  )
}

function amount(s: PatternStack, locale: Locale, t: Translate) {
  return s.kind === 'fluid' ? `${formatBuckets(s.amount, locale)} ${t('ae2.buckets')}` : formatNumber(s.amount, locale)
}

/** Items to offer as icon: what the provider makes and uses, and AE2's own icon for it. */
function iconSuggestions(p: Ae2PatternProvider, patterns: Ae2Pattern[]) {
  const keys = [p.groupIcon, ...patterns.flatMap((x) => [x.output.key, ...x.inputs.map((i) => i.key)])]
  const names = new Map<string, string>()
  for (const x of patterns) {
    names.set(x.output.key, x.output.name)
    for (const i of x.inputs) names.set(i.key, i.name)
  }
  return [...new Set(keys)]
    .filter((k): k is string => !!k && k.startsWith('item:') && !k.includes('#'))
    .slice(0, 40)
    .map((k) => ({ id: k.slice('item:'.length), name: names.get(k) ?? k.slice('item:'.length) }))
}

function ProviderEditor({
  grid,
  provider: p,
  tiers,
  patterns,
  onDone,
}: {
  grid: string
  provider: Ae2PatternProvider
  tiers: Tier[]
  patterns: Ae2Pattern[]
  onDone: () => void
}) {
  const t = useT()
  const run = useRunAction('ae2')
  const [name, setName] = useState(p.name ?? '')
  const [icon, setIcon] = useState(p.icon ?? '')
  const [priority, setPriority] = useState(String(p.priority))
  const [blocking, setBlocking] = useState(p.blocking ? 'on' : 'off')
  const [visible, setVisible] = useState(p.visible ? 'on' : 'off')
  const [lock, setLock] = useState<LockMode>(p.lock)
  const suggestions = useMemo(() => iconSuggestions(p, patterns), [p, patterns])
  const tier = tierOfMachine(name, tiers)
  const parsed = Number(priority)
  const priorityOk = priority.trim() !== '' && Number.isInteger(parsed) && Math.abs(parsed) <= 2_147_483_647
  const iconOk = icon.trim() === '' || ITEM_ID.test(icon.trim())
  const nameOk = name.trim().length <= MAX_AE2_NAME

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const params: Record<string, unknown> = {}
    if (name.trim() !== (p.name ?? '')) params.name = name.trim()
    if (icon.trim() !== (p.icon ?? '')) params.icon = icon.trim()
    if (parsed !== p.priority) params.priority = parsed
    if ((blocking === 'on') !== p.blocking) params.blocking = blocking === 'on'
    if ((visible === 'on') !== p.visible) params.visible = visible === 'on'
    if (lock !== p.lock) params.lock = lock
    if (Object.keys(params).length === 0) return onDone()
    run.mutate({ type: AE2_ACTIONS.providerEdit, resource: grid, params: { provider: p.id, ...params } }, { onSuccess: onDone })
  }

  return (
    <form onSubmit={submit} className="min-w-0 space-y-3 text-sm" data-testid="provider-editor">
      <Field label={t('edit.name')} hint={<Counter value={name.trim()} max={MAX_AE2_NAME} />}>
        <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={plainText(p.group)} aria-invalid={!nameOk} autoFocus />
      </Field>
      <p className="text-muted-foreground text-xs">{t('provider.nameHint')}</p>
      {name.includes('§') && (
        <p>
          <span className="text-muted-foreground text-xs">{t('edit.preview')}: </span>
          <McText text={name} />
        </p>
      )}
      {tiers.length > 0 && (
        <Field label={t('tier.label')}>
          <select
            className={`${SELECT} w-full`}
            value={tier?.name ?? ''}
            onChange={(e) => setName(withTier(name, tiers.find((x) => x.name === e.target.value) ?? null, tiers))}
          >
            <option value="">{t('tier.none')}</option>
            {tiers.map((x) => (
              <option key={x.name} value={x.name}>
                {x.name}
              </option>
            ))}
          </select>
        </Field>
      )}
      <Field label={t('provider.icon')}>
        <div className="flex min-w-0 items-center gap-2">
          <ItemIcon stackKey={icon.trim() ? `item:${icon.trim()}` : providerIcon({ ...p, icon: null })} name={plainText(name || p.group) || '?'} />
          <Input
            value={icon}
            onChange={(e) => setIcon(e.target.value)}
            placeholder="minecraft:furnace"
            list={`icons-${p.id}`}
            aria-invalid={!iconOk}
            className="min-w-0 flex-1"
          />
        </div>
      </Field>
      <datalist id={`icons-${p.id}`}>
        {suggestions.map((s) => (
          <option key={s.id} value={s.id}>
            {plainText(s.name)}
          </option>
        ))}
      </datalist>
      <p className="text-muted-foreground text-xs">{t('provider.iconHint')}</p>
      <Field label={t('drive.priority')}>
        <Input type="number" step={1} value={priority} onChange={(e) => setPriority(e.target.value)} aria-invalid={!priorityOk} />
      </Field>
      <div className="flex min-w-0 flex-wrap gap-4">
        <div className="min-w-0 space-y-1">
          <span className="text-muted-foreground block text-xs font-medium">{t('provider.blocking')}</span>
          <Segmented value={blocking} onChange={setBlocking} options={[{ value: 'on', label: t('edit.on') }, { value: 'off', label: t('edit.off') }]} />
        </div>
        <div className="min-w-0 space-y-1">
          <span className="text-muted-foreground block text-xs font-medium">{t('provider.visible')}</span>
          <Segmented value={visible} onChange={setVisible} options={[{ value: 'on', label: t('edit.on') }, { value: 'off', label: t('edit.off') }]} />
        </div>
      </div>
      <Field label={t('provider.lockMode')}>
        <select className={`${SELECT} w-full`} value={lock} onChange={(e) => setLock(e.target.value as LockMode)}>
          {LOCK_MODES.map((m) => (
            <option key={m} value={m}>
              {t(`provider.lock.${m}`)}
            </option>
          ))}
        </select>
      </Field>
      <Feedback error={run.error ? errorText(t, run.error) : undefined} />
      <Actions pending={run.isPending} onCancel={onDone} disabled={!priorityOk || !iconOk || !nameOk} />
    </form>
  )
}
