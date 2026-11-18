import { ChevronDown, Cpu } from 'lucide-react'
import { useState } from 'react'
import { useEvents, useJob, type JobLine } from '@/api/hooks'
import { Button } from '@/components/ui/button'
import { ItemIcon } from '@/components/item-icon'
import { McText } from '@/components/mc-text'
import { Section } from '@/components/stat'
import { Card } from '@/components/ui/card'
import { Progress } from '@/components/ui/progress'
import { formatBuckets, formatClock, idPos, isInfinite, jobEta, jobProgressKnown, pastJobs, type Ae2Cpu } from '@/lib/ae2'
import { formatBytes, formatNumber, formatSi } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { usePreferences, type Locale } from '@/lib/preferences'
import { cn } from '@/lib/utils'

const JOB_ATTRIBUTES = ['job', 'jobName', 'jobAmount']

/**
 * Crafting CPUs of an ME network, live (pushed every second). Busy CPUs show their job with
 * progress; a card expands to details and the CPU's recent jobs.
 */
export function CraftingSection({ cpus }: { cpus: Ae2Cpu[] }) {
  const t = useT()
  if (cpus.length === 0) return null
  const busy = cpus.filter((c) => c.job).length
  // Unnamed CPUs are numbered by position, so the number stays when a CPU becomes busy.
  const numbers = new Map(
    [...cpus].sort((a, b) => a.id.localeCompare(b.id, undefined, { numeric: true })).map((c, i) => [c.id, i + 1]),
  )
  return (
    <Section
      title={t('cpu.title')}
      aside={<span className="text-muted-foreground shrink-0 text-xs">{t('cpu.busyOf', { busy, count: cpus.length })}</span>}
    >
      <ul className="grid min-w-0 items-start gap-3 md:grid-cols-2 xl:grid-cols-3" data-testid="cpu-list">
        {cpus.map((cpu) => (
          <CpuCard key={cpu.id} cpu={cpu} number={cpu.number || (numbers.get(cpu.id) ?? 0)} />
        ))}
      </ul>
    </Section>
  )
}

function CpuCard({ cpu, number }: { cpu: Ae2Cpu; number: number }) {
  const t = useT()
  const { locale } = usePreferences()
  const [open, setOpen] = useState(false)
  const job = cpu.job
  const known = job ? jobProgressKnown(job) : false
  const share = job && known ? Math.min(100, (job.progress / job.total) * 100) : 0
  const eta = job && known ? jobEta(job) : undefined
  const title = cpu.name ?? t('cpu.unnamed', { n: number })

  return (
    <li className="min-w-0">
      <Card className={cn('min-w-0 gap-0 py-0', !job && 'bg-card/60')}>
        <button
          type="button"
          className="flex min-w-0 flex-col gap-2 px-4 py-3 text-left"
          aria-expanded={open}
          onClick={() => setOpen((o) => !o)}
        >
          <div className="flex min-w-0 items-center gap-2">
            <Cpu className={cn('size-4 shrink-0', job ? 'text-primary' : 'text-muted-foreground')} />
            <span className="min-w-0 flex-1 truncate text-sm font-medium">
              <McText text={title} />
            </span>
            <span className="text-muted-foreground shrink-0 text-xs">{formatBytes(cpu.storage, locale)}</span>
            <ChevronDown className={cn('text-muted-foreground size-4 shrink-0 transition-transform', open && 'rotate-180')} />
          </div>
          {job ? (
            <>
              <div className="flex min-w-0 items-center justify-between gap-2">
                <ItemIcon stackKey={job.key} name={job.name} />
                <span className="min-w-0 flex-1 truncate text-sm font-semibold">
                  <McText text={job.name} />
                </span>
                <span className="tabular shrink-0 text-sm font-semibold">×{amount(job.amount, job.kind, locale, t('ae2.buckets'))}</span>
              </div>
              <Progress
                value={known ? share : 100}
                aria-label={t('cpu.progress')}
                className={cn(!known && 'animate-pulse opacity-50')}
              />
              <div className="text-muted-foreground flex min-w-0 flex-wrap justify-between gap-x-3 text-xs">
                {known ? (
                  <span className="tabular">
                    {formatNumber(share, locale, 0)} % · {formatSi(job.progress, locale)} / {formatSi(job.total, locale)}
                  </span>
                ) : (
                  <span title={t('cpu.unknownHint')}>{t('cpu.unknown')}</span>
                )}
                <span className="tabular">
                  {formatClock(job.elapsed)}
                  {eta !== undefined && ` · ${t('cpu.eta', { eta: formatClock(eta) })}`}
                </span>
              </div>
            </>
          ) : (
            // Same rows as a busy card: cards keep their height when jobs start or end, so the
            // page does not jump while scrolling.
            <>
              <div className="flex min-h-6 min-w-0 items-center">
                <span className="text-muted-foreground text-sm">{t('cpu.idle')}</span>
              </div>
              <Progress value={0} aria-hidden className="opacity-40" />
              <span className="text-xs">&nbsp;</span>
            </>
          )}
        </button>
        {open && <CpuDetails cpu={cpu} />}
      </Card>
    </li>
  )
}

function CpuDetails({ cpu }: { cpu: Ae2Cpu }) {
  const t = useT()
  const { locale } = usePreferences()
  const events = useEvents(cpu.resource.ref.key, JOB_ATTRIBUTES, true, 10_000)
  const jobs = pastJobs(events.data ?? []).filter((j) => j.endedUptime !== undefined).slice(0, 10)
  return (
    <div className="min-w-0 space-y-3 border-t px-4 py-3 text-xs" data-testid="cpu-details">
      <dl className="grid min-w-0 grid-cols-2 gap-x-3 gap-y-1">
        <dt className="text-muted-foreground">{t('ae2.coProcessors')}</dt>
        <dd className="tabular truncate text-right">{formatNumber(cpu.coProcessors, locale)}</dd>
        <dt className="text-muted-foreground">{t('cpu.mode')}</dt>
        <dd className="truncate text-right">{t(`cpu.mode.${cpu.mode}` as 'cpu.mode.any')}</dd>
        <dt className="text-muted-foreground">{t('ae2.location')}</dt>
        <dd className="tabular truncate text-right">{idPos(cpu.id)}</dd>
        {cpu.job && (
          <>
            <dt className="text-muted-foreground">{t('cpu.key')}</dt>
            <dd className="truncate text-right" title={cpu.job.key}>
              {cpu.job.key}
            </dd>
          </>
        )}
      </dl>
      {cpu.job && <JobLines resource={cpu.resource.ref.key} />}
      <div className="min-w-0">
        <p className="text-muted-foreground mb-1 font-medium">{t('cpu.recent')}</p>
        {events.isPending ? (
          <p className="text-muted-foreground">…</p>
        ) : jobs.length === 0 ? (
          <p className="text-muted-foreground">{t('cpu.noRecent')}</p>
        ) : (
          <ul className="min-w-0 space-y-1" data-testid="cpu-recent">
            {jobs.map((j) => (
              <li key={j.startedUptime} className="flex min-w-0 justify-between gap-2">
                <span className="min-w-0 truncate">
                  <McText text={j.name} /> ×{formatNumber(j.amount, locale)}
                </span>
                <span className="text-muted-foreground tabular shrink-0">
                  {new Date(j.started).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })} ·{' '}
                  {formatClock(((j.endedUptime ?? j.startedUptime) - j.startedUptime) / 1000)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

const SHOWN_LINES = 12
const COLUMNS = 'grid grid-cols-[minmax(0,1fr)_3.5rem_3.5rem_3.5rem] gap-x-2'

/** What the running job holds and waits for, like AE2's crafting status screen. */
function JobLines({ resource }: { resource: string }) {
  const t = useT()
  const { locale } = usePreferences()
  const [all, setAll] = useState(false)
  const job = useJob(resource, true)
  const lines = job.data?.lines ?? []
  if (job.isPending) return <p className="text-muted-foreground">…</p>
  if (lines.length === 0) return null
  const shown = all ? lines : lines.slice(0, SHOWN_LINES)
  return (
    <div className="min-w-0">
      <div className={cn(COLUMNS, 'text-muted-foreground mb-1 font-medium')}>
        <span className="truncate">{t('cpu.lines')}</span>
        <span className="truncate text-right" title={t('cpu.activeHint')}>
          {t('cpu.active')}
        </span>
        <span className="truncate text-right" title={t('cpu.pendingHint')}>
          {t('cpu.pending')}
        </span>
        <span className="truncate text-right" title={t('cpu.storedHint')}>
          {t('cpu.stored')}
        </span>
      </div>
      <ul className="min-w-0 space-y-0.5" data-testid="job-lines">
        {shown.map((l) => (
          <Line key={l.key} line={l} locale={locale} />
        ))}
      </ul>
      {lines.length > SHOWN_LINES && (
        <Button variant="ghost" size="sm" className="mt-1 h-7 px-2 text-xs" onClick={() => setAll((a) => !a)}>
          {all ? t('cpu.fewer') : t('cpu.allLines', { count: lines.length })}
        </Button>
      )}
    </div>
  )
}

function Line({ line, locale }: { line: JobLine; locale: Locale }) {
  const cell = (v: number, tone?: string) => (
    <span className={cn('tabular truncate text-right', v === 0 ? 'text-muted-foreground' : tone)}>
      {v === 0 ? '–' : isInfinite(v, line.kind) ? '∞' : line.kind === 'fluid' ? formatBuckets(v, locale) : formatSi(v, locale)}
    </span>
  )
  return (
    <li className={cn(COLUMNS, 'min-w-0 items-center')} title={line.key}>
      <span className="flex min-w-0 items-center gap-1.5">
        <ItemIcon stackKey={line.key} name={line.name} className="size-4 [&>img]:size-4" />
        <span className="min-w-0 truncate">
          <McText text={line.name} />
          {line.kind === 'fluid' && <span className="text-muted-foreground"> (B)</span>}
        </span>
      </span>
      {cell(line.active, 'text-primary font-medium')}
      {cell(line.pending)}
      {cell(line.stored)}
    </li>
  )
}

function amount(value: number, kind: string, locale: Locale, buckets: string) {
  return kind === 'fluid' ? `${formatBuckets(value, locale)} ${buckets}` : formatNumber(value, locale)
}
