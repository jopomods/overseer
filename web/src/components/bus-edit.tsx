import { Pencil, Plus, X } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { useInventory, useRunAction } from '@/api/hooks'
import { Actions, Counter, Feedback, Field } from '@/components/edit-form'
import { Segmented } from '@/components/history-chart'
import { ItemIcon } from '@/components/item-icon'
import { McText } from '@/components/mc-text'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { AE2_ACTIONS, BUS_FILTER_SLOTS, MAX_AE2_NAME, type Ae2Bus } from '@/lib/ae2'
import { errorText, SELECT } from '@/lib/edit'
import { useT, type MessageKey } from '@/lib/i18n'
import { plainText } from '@/lib/mc-text'

type FilterEntry = Ae2Bus['filter'][number]

const REDSTONE = ['ignore', 'low_signal', 'high_signal', 'signal_pulse'] as const
const SCHEDULING = ['default', 'roundrobin', 'random'] as const

/** "Edit bus" under a bus card, opening the editor in place. */
export function BusEditSlot({ grid, bus }: { grid: string; bus: Ae2Bus }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  if (open) return <BusEditor grid={grid} bus={bus} onDone={() => setOpen(false)} />
  return (
    <Button size="sm" variant="ghost" className="self-start" onClick={() => setOpen(true)}>
      <Pencil /> {t('bus.edit')}
    </Button>
  )
}

/**
 * Settings of an import or export bus (ae2.bus.edit): name, filter (items from the network's
 * contents), redstone mode, and for export buses craft only and scheduling.
 */
export function BusEditor({ grid, bus, onDone }: { grid: string; bus: Ae2Bus; onDone: () => void }) {
  const t = useT()
  const run = useRunAction('ae2')
  const exportBus = bus.kind === 'export'
  const [name, setName] = useState(bus.name ?? '')
  const [filter, setFilter] = useState<FilterEntry[]>(bus.filter)
  const [redstone, setRedstone] = useState(bus.redstone)
  const [craftOnly, setCraftOnly] = useState(bus.craftOnly ? 'on' : 'off')
  const [scheduling, setScheduling] = useState(bus.scheduling ?? 'default')
  const nameOk = name.trim().length <= MAX_AE2_NAME

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const params: Record<string, unknown> = {}
    if (name.trim() !== (bus.name ?? '')) params.name = name.trim()
    if (filter.map((f) => f.key).join('|') !== bus.filter.map((f) => f.key).join('|')) params.filter = filter.map((f) => f.key)
    if (redstone !== bus.redstone) params.redstone = redstone
    if (exportBus && (craftOnly === 'on') !== Boolean(bus.craftOnly)) params.craftOnly = craftOnly === 'on'
    if (exportBus && scheduling !== (bus.scheduling ?? 'default')) params.scheduling = scheduling
    if (Object.keys(params).length === 0) return onDone()
    run.mutate({ type: AE2_ACTIONS.busEdit, resource: grid, params: { bus: bus.id, ...params } }, { onSuccess: onDone })
  }

  return (
    <form onSubmit={submit} className="min-w-0 space-y-3 border-t pt-3 text-sm" data-testid="bus-editor">
      <Field label={t('edit.name')} hint={<Counter value={name.trim()} max={MAX_AE2_NAME} />}>
        <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={plainText(bus.target)} aria-invalid={!nameOk} autoFocus />
      </Field>
      <div className="min-w-0 space-y-1.5">
        <span className="text-muted-foreground flex justify-between text-xs font-medium">
          <span>{t('bus.filter')}</span>
          <span className="tabular">
            {filter.length}/{BUS_FILTER_SLOTS}
          </span>
        </span>
        {filter.length > 0 ? (
          <ul className="flex min-w-0 flex-wrap gap-1" data-testid="bus-filter-edit">
            {filter.map((f) => (
              <li key={f.key} className="bg-muted flex min-w-0 items-center gap-1 rounded-md py-0.5 pr-0.5 pl-1">
                <ItemIcon stackKey={f.key} name={f.name} className="size-4 [&>img]:size-4" />
                <span className="max-w-32 truncate text-xs">
                  <McText text={f.name} />
                </span>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  className="size-5"
                  aria-label={t('bus.removeFilter', { name: plainText(f.name) })}
                  onClick={() => setFilter((list) => list.filter((x) => x.key !== f.key))}
                >
                  <X />
                </Button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-muted-foreground text-xs">{exportBus ? t('bus.noFilterExport') : t('bus.everything')}</p>
        )}
        {filter.length < BUS_FILTER_SLOTS && (
          <AddFilter grid={grid} exclude={filter.map((f) => f.key)} onAdd={(f) => setFilter((list) => [...list, f])} />
        )}
        <p className="text-muted-foreground text-xs">{t('bus.filterHint')}</p>
      </div>
      <Field label={t('bus.redstoneMode')}>
        <select className={`${SELECT} w-full`} value={redstone} onChange={(e) => setRedstone(e.target.value)}>
          {REDSTONE.map((r) => (
            <option key={r} value={r}>
              {t(`bus.redstone.${r}` as MessageKey)}
            </option>
          ))}
        </select>
      </Field>
      {exportBus && (
        <div className="flex min-w-0 flex-wrap gap-4">
          <div className="min-w-0 space-y-1">
            <span className="text-muted-foreground block text-xs font-medium">{t('bus.craftOnly')}</span>
            <Segmented value={craftOnly} onChange={setCraftOnly} options={[{ value: 'on', label: t('edit.on') }, { value: 'off', label: t('edit.off') }]} />
          </div>
          <Field label={t('bus.schedulingMode')}>
            <select className={SELECT} value={scheduling} onChange={(e) => setScheduling(e.target.value)}>
              {SCHEDULING.map((s) => (
                <option key={s} value={s}>
                  {t(`bus.scheduling.${s}` as MessageKey)}
                </option>
              ))}
            </select>
          </Field>
        </div>
      )}
      <Feedback error={run.error ? errorText(t, run.error) : undefined} />
      <Actions pending={run.isPending} onCancel={onDone} disabled={!nameOk} />
    </form>
  )
}

/** Search the network's contents and add a match to the filter. */
function AddFilter({ grid, exclude, onAdd }: { grid: string; exclude: string[]; onAdd: (f: FilterEntry) => void }) {
  const t = useT()
  const [query, setQuery] = useState('')
  const [q, setQ] = useState('')
  useEffect(() => {
    const timer = setTimeout(() => setQ(query.trim()), 250)
    return () => clearTimeout(timer)
  }, [query])
  const results = useInventory(grid, { q, sort: 'amount', limit: 8 })
  const matches = q ? (results.data?.items ?? []).filter((i) => !exclude.includes(i.key)).slice(0, 6) : []
  return (
    <div className="min-w-0 space-y-1">
      <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t('bus.addFilter')} aria-label={t('bus.addFilter')} />
      {matches.length > 0 && (
        <ul className="border-border min-w-0 rounded-md border" data-testid="bus-filter-matches">
          {matches.map((m) => (
            <li key={m.key} className="min-w-0">
              <button
                type="button"
                className="hover:bg-muted/50 flex w-full min-w-0 items-center gap-2 px-2 py-1 text-left"
                onClick={() => {
                  onAdd({ key: m.key, kind: m.kind, name: m.name })
                  setQuery('')
                }}
              >
                <ItemIcon stackKey={m.key} name={m.name} className="size-4 [&>img]:size-4" />
                <span className="min-w-0 flex-1 truncate text-xs">
                  <McText text={m.name} />
                </span>
                <Plus className="text-muted-foreground size-3.5" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
