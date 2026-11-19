import { Hammer, Play, Search, X } from 'lucide-react'
import { createContext, useContext, useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { useRunAction } from '@/api/hooks'
import { Feedback, Field } from '@/components/edit-form'
import { ItemIcon } from '@/components/item-icon'
import { McText } from '@/components/mc-text'
import { Section } from '@/components/stat'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { AE2_ACTIONS, formatBuckets, type Ae2Pattern, type CraftPlan, type PatternStack } from '@/lib/ae2'
import { errorText, SELECT } from '@/lib/edit'
import { formatBytes, formatNumber } from '@/lib/format'
import { useT, type Translate } from '@/lib/i18n'
import { plainText } from '@/lib/mc-text'
import { usePreferences, type Locale } from '@/lib/preferences'
import { cn } from '@/lib/utils'

/** What to craft: a stack key, its name and kind, and an amount to start with (mB for fluids). */
export interface CraftRequest {
  key: string
  name: string
  kind: string
  amount?: number
}

interface CraftState {
  canCraft: (key: string) => boolean
  open: (request: CraftRequest) => void
}

const CraftContext = createContext<CraftState | null>(null)

/** Lets any list on the ME network page open the craft panel (ADR-0019). */
export function useCraft(): CraftState | null {
  return useContext(CraftContext)
}

/**
 * Remote crafting on one ME network: plan a job like AE2's craft confirm screen, see what it uses and
 * misses, pick a CPU, start it. Only shown when the server allows the viewer to craft here.
 */
export function CraftProvider({
  grid,
  enabled,
  patterns,
  children,
}: {
  grid: string
  enabled: boolean
  patterns: Ae2Pattern[]
  children: ReactNode
}) {
  const [request, setRequest] = useState<CraftRequest | null>(null)
  const [opened, setOpened] = useState(0)
  const craftables = useMemo(() => {
    const map = new Map<string, PatternStack>()
    for (const p of patterns) if (!map.has(p.output.key)) map.set(p.output.key, p.output)
    return map
  }, [patterns])
  const state = useMemo<CraftState | null>(
    () =>
      enabled
        ? {
            canCraft: (key) => craftables.has(key),
            open: (r) => {
              setRequest(r)
              setOpened((n) => n + 1)
            },
          }
        : null,
    [enabled, craftables],
  )
  return (
    <CraftContext.Provider value={state}>
      {enabled && (
        <CraftSection
          grid={grid}
          craftables={craftables}
          request={request}
          opened={opened}
          onRequest={(r) => setRequest(r)}
        />
      )}
      {children}
    </CraftContext.Provider>
  )
}

/** A small button that opens the craft panel for one item; nothing when it cannot be crafted here. */
export function CraftButton({ request, className }: { request: CraftRequest; className?: string }) {
  const t = useT()
  const craft = useCraft()
  if (!craft?.canCraft(request.key)) return null
  return (
    <Button
      variant="ghost"
      size="icon-sm"
      className={className}
      aria-label={t('craft.open', { name: plainText(request.name) })}
      title={t('craft.open', { name: plainText(request.name) })}
      onClick={(e) => {
        e.stopPropagation()
        craft.open(request)
      }}
    >
      <Hammer />
    </Button>
  )
}

function CraftSection({
  grid,
  craftables,
  request,
  opened,
  onRequest,
}: {
  grid: string
  craftables: Map<string, PatternStack>
  request: CraftRequest | null
  opened: number
  onRequest: (r: CraftRequest | null) => void
}) {
  const t = useT()
  const ref = useRef<HTMLDivElement>(null)
  const [searching, setSearching] = useState(false)
  useEffect(() => {
    if (opened > 0) ref.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, [opened])
  if (craftables.size === 0) return null
  return (
    <div ref={ref} className="scroll-mt-4">
      <Section
        title={t('craft.title')}
        aside={
          !request &&
          !searching && (
            <Button size="sm" variant="outline" onClick={() => setSearching(true)} data-testid="craft-search-open">
              <Hammer /> {t('craft.something')}
            </Button>
          )
        }
      >
        {request ? (
          <CraftPanel key={`${request.key}-${opened}`} grid={grid} request={request} onClose={() => onRequest(null)} />
        ) : searching ? (
          <CraftSearch
            craftables={craftables}
            onPick={(s) => {
              setSearching(false)
              onRequest({ key: s.key, name: s.name, kind: s.kind })
            }}
            onClose={() => setSearching(false)}
          />
        ) : (
          <p className="text-muted-foreground text-sm">{t('craft.hint', { count: craftables.size })}</p>
        )}
      </Section>
    </div>
  )
}

const SEARCH_SHOWN = 20

function CraftSearch({
  craftables,
  onPick,
  onClose,
}: {
  craftables: Map<string, PatternStack>
  onPick: (s: PatternStack) => void
  onClose: () => void
}) {
  const t = useT()
  const [query, setQuery] = useState('')
  const q = query.trim().toLowerCase()
  const matches = [...craftables.values()]
    .filter((s) => !q || plainText(s.name).toLowerCase().includes(q) || s.key.includes(q))
    .sort((a, b) => plainText(a.name).localeCompare(plainText(b.name)))
  return (
    <Card className="min-w-0 gap-0 py-0" data-testid="craft-search">
      <div className="flex min-w-0 items-center gap-2 border-b p-3">
        <div className="relative min-w-0 flex-1">
          <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2" />
          <Input
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t('craft.search')}
            aria-label={t('craft.search')}
            className="pl-8"
          />
        </div>
        <Button variant="ghost" size="icon-sm" aria-label={t('common.cancel')} onClick={onClose}>
          <X />
        </Button>
      </div>
      {matches.length === 0 ? (
        <p className="text-muted-foreground p-4 text-sm">{t('craft.noMatch')}</p>
      ) : (
        <ul className="divide-border min-w-0 divide-y">
          {matches.slice(0, SEARCH_SHOWN).map((s) => (
            <li key={s.key} className="min-w-0">
              <button type="button" className="hover:bg-muted/50 flex w-full min-w-0 items-center gap-2 px-4 py-2 text-left" onClick={() => onPick(s)}>
                <ItemIcon stackKey={s.key} name={s.name} />
                <span className="min-w-0 flex-1 truncate text-sm">
                  <McText text={s.name} />
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {matches.length > SEARCH_SHOWN && (
        <p className="text-muted-foreground border-t px-4 py-2 text-xs">{t('craft.more', { count: matches.length - SEARCH_SHOWN })}</p>
      )}
    </Card>
  )
}

/** Amount as the player types it: items, or buckets for fluids (AE2 counts fluids in mB). */
function initialAmount(request: CraftRequest): string {
  if (!request.amount || request.amount <= 0) return request.kind === 'fluid' ? '1' : '1'
  return request.kind === 'fluid' ? String(Math.ceil(request.amount / 1000)) : String(Math.ceil(request.amount))
}

function CraftPanel({ grid, request, onClose }: { grid: string; request: CraftRequest; onClose: () => void }) {
  const t = useT()
  const { locale } = usePreferences()
  const planRun = useRunAction('ae2')
  const startRun = useRunAction('ae2')
  const [amount, setAmount] = useState(() => initialAmount(request))
  const [plan, setPlan] = useState<CraftPlan | null>(null)
  const [cpu, setCpu] = useState('')
  const [started, setStarted] = useState<string | null>(null)
  const fluid = request.kind === 'fluid'
  const parsed = Number(amount)
  const valid = amount.trim() !== '' && Number.isInteger(parsed) && parsed >= 1 && parsed <= (fluid ? 1e9 : 1e12)

  const submitPlan = (event?: FormEvent) => {
    event?.preventDefault()
    if (!valid) return
    setPlan(null)
    setStarted(null)
    startRun.reset()
    planRun.mutate(
      { type: AE2_ACTIONS.craftPlan, resource: grid, params: { key: request.key, amount: fluid ? parsed * 1000 : parsed } },
      {
        onSuccess: (result) => {
          setPlan((result.data ?? null) as CraftPlan | null)
          setCpu('')
        },
      },
    )
  }
  const start = () => {
    if (!plan) return
    startRun.mutate(
      { type: AE2_ACTIONS.craftStart, resource: grid, params: { plan: plan.plan, ...(cpu ? { cpu } : {}) } },
      {
        onSuccess: (result) => {
          const on = result.changes[0]?.resource
          const chosen = plan.cpus.find((c) => on === `ae2/cpu/${c.id}`)
          setStarted(chosen ? cpuName(chosen, t) : '')
          setPlan(null)
        },
      },
    )
  }

  return (
    <Card className="min-w-0 gap-3 px-4 py-4" data-testid="craft-panel">
      <div className="flex min-w-0 items-center gap-2">
        <ItemIcon stackKey={request.key} name={request.name} />
        <span className="min-w-0 flex-1 truncate font-medium">
          <McText text={request.name} />
        </span>
        <Button variant="ghost" size="icon-sm" aria-label={t('common.cancel')} onClick={onClose}>
          <X />
        </Button>
      </div>
      <form onSubmit={submitPlan} className="flex min-w-0 flex-wrap items-end gap-2">
        <div className="min-w-0 flex-1 basis-40">
          <Field label={fluid ? t('craft.amountBuckets') : t('craft.amount')}>
            <Input type="number" min={1} step={1} value={amount} onChange={(e) => setAmount(e.target.value)} aria-invalid={!valid} autoFocus />
          </Field>
        </div>
        <Button type="submit" size="sm" className="shrink-0" disabled={!valid || planRun.isPending}>
          {planRun.isPending ? t('craft.planning') : t('craft.plan')}
        </Button>
      </form>
      <Feedback error={planRun.error ? errorText(t, planRun.error) : startRun.error ? errorText(t, startRun.error) : undefined} />
      {started !== null && (
        <p className="text-success text-sm" role="status" data-testid="craft-started">
          {started ? t('craft.startedOn', { cpu: started }) : t('craft.started')}
        </p>
      )}
      {plan && <PlanView plan={plan} cpu={cpu} onCpu={setCpu} onStart={start} pending={startRun.isPending} locale={locale} />}
    </Card>
  )
}

type PlanCpu = CraftPlan['cpus'][number]

function cpuName(c: PlanCpu, t: Translate) {
  return c.name ? plainText(c.name) : t('cpu.unnamed', { n: c.number })
}

function PlanView({
  plan,
  cpu,
  onCpu,
  onStart,
  pending,
  locale,
}: {
  plan: CraftPlan
  cpu: string
  onCpu: (id: string) => void
  onStart: () => void
  pending: boolean
  locale: Locale
}) {
  const t = useT()
  const usable = plan.cpus.filter((c) => !c.busy && c.fits)
  const chosen = plan.cpus.find((c) => c.id === cpu)
  const blocked = plan.missing || usable.length === 0 || (chosen !== undefined && (chosen.busy || !chosen.fits))
  return (
    <div className="min-w-0 space-y-3" data-testid="craft-plan">
      <div className="flex min-w-0 flex-wrap items-center gap-2 text-sm">
        <span className="font-medium">
          ×{amountText(plan.output, locale, t)} · {formatBytes(plan.bytes, locale)}
        </span>
        {plan.missing ? (
          <Badge variant="destructive">{t('craft.missing')}</Badge>
        ) : (
          <Badge variant="secondary">{t('craft.ready')}</Badge>
        )}
        {plan.multiplePaths && <Badge variant="outline">{t('craft.multiplePaths')}</Badge>}
      </div>
      {plan.missingItems.length > 0 && <Lines title={t('craft.missingItems')} lines={plan.missingItems} tone="text-destructive" testId="craft-missing" />}
      <Lines title={t('craft.used')} lines={plan.used} testId="craft-used" />
      {plan.crafted.length > 0 && <Lines title={t('craft.crafted')} lines={plan.crafted} testId="craft-crafted" />}
      {!plan.missing && (
        <div className="flex min-w-0 flex-wrap items-end gap-2">
          <div className="min-w-0 flex-1 basis-56">
            <Field label={t('craft.cpu')}>
              <select className={`${SELECT} w-full`} value={cpu} onChange={(e) => onCpu(e.target.value)}>
                <option value="">{t('craft.cpuAuto')}</option>
                {plan.cpus.map((c) => (
                  <option key={c.id} value={c.id} disabled={c.busy || !c.fits}>
                    {cpuName(c, t)} · {formatBytes(c.storage, locale)}
                    {c.busy ? ` · ${t('craft.busy')}` : !c.fits ? ` · ${t('craft.tooSmall')}` : ''}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          <Button size="sm" className="shrink-0" disabled={blocked || pending} onClick={onStart}>
            <Play /> {t('craft.start')}
          </Button>
        </div>
      )}
      {!plan.missing && usable.length === 0 && <p className="text-warning text-xs">{t('craft.noCpu')}</p>}
      <p className="text-muted-foreground text-xs">{t('craft.expires', { minutes: Math.round(plan.expiresInSeconds / 60) })}</p>
    </div>
  )
}

const LINES_SHOWN = 8

function Lines({ title, lines, tone, testId }: { title: string; lines: PatternStack[]; tone?: string; testId: string }) {
  const t = useT()
  const { locale } = usePreferences()
  const [all, setAll] = useState(false)
  if (lines.length === 0) return null
  const shown = all ? lines : lines.slice(0, LINES_SHOWN)
  return (
    <div className="min-w-0 text-xs" data-testid={testId}>
      <p className="text-muted-foreground mb-1 font-medium">{title}</p>
      <ul className="grid min-w-0 gap-x-4 gap-y-0.5 sm:grid-cols-2">
        {shown.map((l) => (
          <li key={l.key} className="flex min-w-0 items-center gap-1.5" title={l.key}>
            <ItemIcon stackKey={l.key} name={l.name} className="size-4 [&>img]:size-4" />
            <span className="min-w-0 flex-1 truncate">
              <McText text={l.name} />
            </span>
            <span className={cn('tabular shrink-0', tone)}>{amountText(l, locale, t)}</span>
          </li>
        ))}
      </ul>
      {lines.length > LINES_SHOWN && (
        <Button variant="ghost" size="sm" className="mt-1 h-7 px-2 text-xs" onClick={() => setAll((a) => !a)}>
          {all ? t('cpu.fewer') : t('cpu.allLines', { count: lines.length })}
        </Button>
      )}
    </div>
  )
}

function amountText(s: PatternStack, locale: Locale, t: Translate) {
  return s.kind === 'fluid' ? `${formatBuckets(s.amount, locale)} ${t('ae2.buckets')}` : formatNumber(s.amount, locale)
}
