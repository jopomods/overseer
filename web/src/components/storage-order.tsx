import { Pencil, Search, X } from 'lucide-react'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useInventory, useRunAction } from '@/api/hooks'
import { AddFilter } from '@/components/bus-edit'
import { Actions, Counter, Feedback, Field } from '@/components/edit-form'
import { Segmented } from '@/components/history-chart'
import { ItemIcon } from '@/components/item-icon'
import { McText } from '@/components/mc-text'
import { Section } from '@/components/stat'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { AE2_ACTIONS, idPos, MAX_AE2_NAME, type Ae2Drive } from '@/lib/ae2'
import { errorText, SELECT } from '@/lib/edit'
import { useT, type MessageKey } from '@/lib/i18n'
import { plainText } from '@/lib/mc-text'
import {
  ACCESS_MODES,
  destination,
  OPEN,
  STORAGE_BUS_SLOTS,
  storageOrder,
  type Ae2StorageBus,
  type Partition,
  type PartitionKey,
  type StorageEntry,
} from '@/lib/storage'
import { cn } from '@/lib/utils'

const SHOWN = 12
const CHIPS = 6

/**
 * Where items go in an ME network: drives and storage buses by priority (AE2 fills the highest
 * first), what each accepts, and for a chosen item which storage takes it first.
 */
export function StorageOrderSection({
  grid,
  drives,
  buses,
  editable,
}: {
  grid: string
  drives: Ae2Drive[]
  buses: Ae2StorageBus[]
  editable: boolean
}) {
  const t = useT()
  const [item, setItem] = useState<PartitionKey | null>(null)
  const [all, setAll] = useState(false)
  const entries = useMemo(() => storageOrder(drives, buses), [drives, buses])
  if (entries.length === 0) return null
  const where = item ? destination(entries, item.key) : null
  const shown = all ? entries : entries.slice(0, SHOWN)
  const priorities = new Set(entries.map((e) => e.priority)).size

  return (
    <Section
      title={t('order.title')}
      aside={<span className="text-muted-foreground shrink-0 text-xs">{t('order.summary', { count: entries.length, priorities })}</span>}
    >
      <p className="text-muted-foreground mb-2 text-sm">{t('order.hint')}</p>
      <WhereDoesItGo grid={grid} item={item} onPick={setItem} />
      {item && where && (
        <p className={cn('mb-3 text-sm', where.first.size === 0 && 'text-destructive font-medium')} data-testid="storage-answer">
          {where.first.size === 0
            ? t('order.nowhere', { name: plainText(item.name) })
            : t('order.goesTo', { name: plainText(item.name), count: where.first.size })}
        </p>
      )}
      <ol className="grid min-w-0 items-start gap-3 md:grid-cols-2 xl:grid-cols-3" data-testid="storage-order">
        {shown.map((e) => (
          <EntryCard
            key={`${e.kind}:${e.id}`}
            grid={grid}
            entry={e}
            editable={editable}
            mark={!where ? undefined : where.first.has(e.id) ? 'first' : where.accepting.has(e.id) ? 'accepts' : 'refuses'}
          />
        ))}
      </ol>
      {entries.length > SHOWN && (
        <Button variant="ghost" size="sm" className="mt-2" onClick={() => setAll((a) => !a)}>
          {all ? t('cpu.fewer') : t('cpu.allLines', { count: entries.length })}
        </Button>
      )}
    </Section>
  )
}

/** Pick an item of the network to see where AE2 would put it. */
function WhereDoesItGo({ grid, item, onPick }: { grid: string; item: PartitionKey | null; onPick: (item: PartitionKey | null) => void }) {
  const t = useT()
  const [query, setQuery] = useState('')
  const [q, setQ] = useState('')
  useEffect(() => {
    const timer = setTimeout(() => setQ(query.trim()), 250)
    return () => clearTimeout(timer)
  }, [query])
  const results = useInventory(grid, { q, sort: 'amount', limit: 6 })
  const matches = q ? (results.data?.items ?? []).slice(0, 6) : []
  if (item) {
    return (
      <div className="mb-2 flex min-w-0 items-center gap-2 text-sm">
        <span className="text-muted-foreground shrink-0">{t('order.where')}</span>
        <ItemIcon stackKey={item.key} name={item.name} className="size-5 [&>img]:size-5" />
        <span className="min-w-0 truncate font-medium">
          <McText text={item.name} />
        </span>
        <Button variant="ghost" size="icon-sm" aria-label={t('order.clear')} onClick={() => onPick(null)}>
          <X />
        </Button>
      </div>
    )
  }
  return (
    <div className="mb-3 min-w-0 space-y-1 sm:max-w-96">
      <div className="relative min-w-0">
        <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2" />
        <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t('order.where')} aria-label={t('order.where')} className="pl-8" />
      </div>
      {matches.length > 0 && (
        <ul className="border-border min-w-0 rounded-md border" data-testid="storage-matches">
          {matches.map((m) => (
            <li key={m.key} className="min-w-0">
              <button
                type="button"
                className="hover:bg-muted/50 flex w-full min-w-0 items-center gap-2 px-2 py-1 text-left"
                onClick={() => {
                  onPick({ key: m.key, kind: m.kind, name: m.name })
                  setQuery('')
                }}
              >
                <ItemIcon stackKey={m.key} name={m.name} className="size-4 [&>img]:size-4" />
                <span className="min-w-0 flex-1 truncate text-xs">
                  <McText text={m.name} />
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function EntryCard({ grid, entry, editable, mark }: { grid: string; entry: StorageEntry; editable: boolean; mark?: 'first' | 'accepts' | 'refuses' }) {
  const t = useT()
  const [editing, setEditing] = useState(false)
  const pos = (id: string) => {
    const slash = id.indexOf('/', id.indexOf('@'))
    const side = slash < 0 ? null : id.slice(slash + 1)
    return `${idPos(slash < 0 ? id : id.slice(0, slash))}${side ? ` · ${t(`provider.side.${side}` as MessageKey)}` : ''}`
  }
  return (
    <li className="min-w-0">
      <Card
        className={cn(
          'min-w-0 gap-2 px-4 py-3',
          mark === 'first' && 'border-primary ring-primary/30 ring-2',
          mark === 'refuses' && 'opacity-60',
        )}
        data-mark={mark}
      >
        <div className="flex min-w-0 items-center gap-2">
          <span className="bg-muted tabular shrink-0 rounded px-1.5 py-0.5 text-xs font-semibold" title={t('order.priority')}>
            {entry.priority}
          </span>
          {entry.kind === 'drive' ? (
            <>
              <ItemIcon stackKey={`item:${entry.drive.block}`} name={entry.drive.block} />
              <span className="min-w-0 flex-1 truncate text-sm font-medium">{t(entry.drive.block === 'ae2:chest' ? 'order.chest' : 'order.drive')}</span>
            </>
          ) : (
            <>
              <ItemIcon stackKey={`item:${entry.bus.block}`} name={entry.bus.block} />
              <span className="min-w-0 flex-1 truncate text-sm font-medium">
                <McText text={entry.bus.name || entry.bus.target || t('order.bus')} />
              </span>
            </>
          )}
          {mark === 'first' && <Badge className="shrink-0">{t('order.first')}</Badge>}
          {mark === 'refuses' && (
            <Badge variant="outline" className="shrink-0">
              {t('order.refuses')}
            </Badge>
          )}
        </div>
        <span className="text-muted-foreground tabular text-xs">{pos(entry.id)}</span>
        {entry.kind === 'drive' ? <DrivePartitions entry={entry} /> : <BusDetails bus={entry.bus} />}
        {entry.kind === 'bus' &&
          editable &&
          (editing ? (
            <StorageBusEditor grid={grid} bus={entry.bus} onDone={() => setEditing(false)} />
          ) : (
            <Button size="sm" variant="ghost" className="self-start" onClick={() => setEditing(true)}>
              <Pencil /> {t('order.edit')}
            </Button>
          ))}
      </Card>
    </li>
  )
}

function DrivePartitions({ entry }: { entry: Extract<StorageEntry, { kind: 'drive' }> }) {
  const t = useT()
  const cells = entry.drive.cells
  const partitioned = cells.filter((c) => entry.partitions.has(c.slot))
  return (
    <div className="min-w-0 space-y-1.5 text-xs">
      <span className="text-muted-foreground">
        {t('order.cells', { count: cells.length })}
        {partitioned.length < cells.length && ` · ${t('order.openCells', { count: cells.length - partitioned.length })}`}
      </span>
      {partitioned.map((c) => (
        <div key={c.slot} className="min-w-0 space-y-1">
          <span className="text-muted-foreground block">{t('order.cell', { slot: c.slot + 1 })}</span>
          <Chips partition={entry.partitions.get(c.slot) ?? OPEN} />
        </div>
      ))}
    </div>
  )
}

function BusDetails({ bus }: { bus: Ae2StorageBus }) {
  const t = useT()
  return (
    <div className="min-w-0 space-y-1.5 text-xs">
      <div className="text-muted-foreground flex min-w-0 flex-wrap gap-x-2">
        {bus.name && bus.target && (
          <span className="min-w-0 truncate">
            <McText text={bus.target} />
          </span>
        )}
        <span>{t(`order.access.${bus.access}` as MessageKey)}</span>
        {!bus.active && <span className="text-warning">{t('order.inactive')}</span>}
      </div>
      <Chips partition={bus.partition} />
    </div>
  )
}

/** A partition as item chips; "accepts everything" when open. */
function Chips({ partition }: { partition: Partition }) {
  const t = useT()
  const [all, setAll] = useState(false)
  if (partition.keys.length === 0) return <span className="text-muted-foreground block text-xs">{t('order.everything')}</span>
  const shown = all ? partition.keys : partition.keys.slice(0, CHIPS)
  return (
    <div className="min-w-0 space-y-1">
      {(partition.exclude || partition.fuzzy) && (
        <span className="text-muted-foreground block text-xs">
          {[partition.exclude && t('order.exclude'), partition.fuzzy && t('order.fuzzy')].filter(Boolean).join(' · ')}
        </span>
      )}
      <ul className="flex min-w-0 flex-wrap gap-1">
        {shown.map((k) => (
          <li key={k.key} className="bg-muted flex min-w-0 items-center gap-1 rounded-md py-0.5 pr-1.5 pl-1">
            <ItemIcon stackKey={k.key} name={k.name} className="size-4 [&>img]:size-4" />
            <span className="max-w-32 truncate text-xs">
              <McText text={k.name} />
            </span>
          </li>
        ))}
        {partition.keys.length > CHIPS && (
          <li>
            <button type="button" className="text-muted-foreground hover:text-foreground px-1 text-xs" onClick={() => setAll((a) => !a)}>
              {all ? t('cpu.fewer') : t('order.more', { count: partition.keys.length - CHIPS })}
            </button>
          </li>
        )}
      </ul>
    </div>
  )
}

/** Name, priority, partition and access of a storage bus (ae2.storagebus.edit). */
function StorageBusEditor({ grid, bus, onDone }: { grid: string; bus: Ae2StorageBus; onDone: () => void }) {
  const t = useT()
  const run = useRunAction('ae2')
  const [name, setName] = useState(bus.name ?? '')
  const [priority, setPriority] = useState(String(bus.priority))
  const [filter, setFilter] = useState<PartitionKey[]>(bus.partition.keys)
  const [access, setAccess] = useState<string>(bus.access)
  const [extractable, setExtractable] = useState(bus.extractableOnly ? 'on' : 'off')
  const nameOk = name.trim().length <= MAX_AE2_NAME
  const priorityValue = Number(priority)
  const priorityOk = priority.trim() !== '' && Number.isInteger(priorityValue) && Math.abs(priorityValue) <= 2 ** 31 - 1

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const params: Record<string, unknown> = {}
    if (name.trim() !== (bus.name ?? '')) params.name = name.trim()
    if (priorityValue !== bus.priority) params.priority = priorityValue
    if (filter.map((f) => f.key).join('|') !== bus.partition.keys.map((f) => f.key).join('|')) params.filter = filter.map((f) => f.key)
    if (access !== bus.access) params.access = access
    if ((extractable === 'on') !== bus.extractableOnly) params.extractableOnly = extractable === 'on'
    if (Object.keys(params).length === 0) return onDone()
    run.mutate({ type: AE2_ACTIONS.storageBusEdit, resource: grid, params: { bus: bus.id, ...params } }, { onSuccess: onDone })
  }

  return (
    <form onSubmit={submit} className="min-w-0 space-y-3 border-t pt-3 text-sm" data-testid="storage-bus-editor">
      <Field label={t('edit.name')} hint={<Counter value={name.trim()} max={MAX_AE2_NAME} />}>
        <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={plainText(bus.target)} aria-invalid={!nameOk} autoFocus />
      </Field>
      <Field label={t('order.priority')}>
        <Input inputMode="numeric" value={priority} onChange={(e) => setPriority(e.target.value)} aria-invalid={!priorityOk} />
      </Field>
      <div className="min-w-0 space-y-1.5">
        <span className="text-muted-foreground flex justify-between text-xs font-medium">
          <span>{t('order.partition')}</span>
          <span className="tabular">
            {filter.length}/{STORAGE_BUS_SLOTS}
          </span>
        </span>
        {filter.length > 0 ? (
          <ul className="flex min-w-0 flex-wrap gap-1" data-testid="storage-partition-edit">
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
          <p className="text-muted-foreground text-xs">{t('order.everything')}</p>
        )}
        {filter.length < STORAGE_BUS_SLOTS && (
          <AddFilter grid={grid} exclude={filter.map((f) => f.key)} onAdd={(f) => setFilter((list) => [...list, f])} />
        )}
      </div>
      <div className="flex min-w-0 flex-wrap gap-4">
        <Field label={t('order.accessLabel')}>
          <select className={SELECT} value={access} onChange={(e) => setAccess(e.target.value)}>
            {ACCESS_MODES.map((m) => (
              <option key={m} value={m}>
                {t(`order.access.${m}` as MessageKey)}
              </option>
            ))}
          </select>
        </Field>
        <div className="min-w-0 space-y-1">
          <span className="text-muted-foreground block text-xs font-medium">{t('order.extractableOnly')}</span>
          <Segmented value={extractable} onChange={setExtractable} options={[{ value: 'on', label: t('edit.on') }, { value: 'off', label: t('edit.off') }]} />
        </div>
      </div>
      <Feedback error={run.error ? errorText(t, run.error) : undefined} />
      <Actions pending={run.isPending} onCancel={onDone} disabled={!nameOk || !priorityOk} />
    </form>
  )
}
