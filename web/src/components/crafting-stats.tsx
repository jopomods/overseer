import { useMemo, useState } from 'react'
import { useJobStats } from '@/api/hooks'
import { Segmented } from '@/components/history-chart'
import { ItemIcon } from '@/components/item-icon'
import { McText } from '@/components/mc-text'
import { Section } from '@/components/stat'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Progress } from '@/components/ui/progress'
import { formatBuckets, type Ae2Cpu } from '@/lib/ae2'
import { formatDuration, formatNumber, formatSi } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { usePreferences } from '@/lib/preferences'

const HOUR = 3_600_000
const RANGES = [
  { ms: 24 * HOUR, label: '24 h' },
  { ms: 7 * 24 * HOUR, label: '7 d' },
  { ms: 30 * 24 * HOUR, label: '30 d' },
]
const MINUTE = 60_000
const SHOWN = 10
/** The server answers for at most this many CPUs per request. */
const MAX_CPUS = 64

/**
 * What the crafting CPUs of a network did (ADR-0022): jobs per item with amounts and durations, and
 * how busy each CPU was. Computed from the recorded job history.
 */
export function CraftingStatsSection({ grid, cpus, uptime }: { grid: string; cpus: Ae2Cpu[]; uptime: number | undefined }) {
  const t = useT()
  const { locale } = usePreferences()
  const [range, setRange] = useState(RANGES[0].ms)
  const [all, setAll] = useState(false)
  const ids = useMemo(() => cpus.slice(0, MAX_CPUS).map((c) => c.resource.ref.key), [cpus])
  // Whole minutes, so the query changes once a minute and not with every live update.
  const to = uptime === undefined ? undefined : Math.floor(uptime / MINUTE) * MINUTE + MINUTE
  const from = to === undefined ? undefined : Math.max(0, to - range)
  const stats = useJobStats(grid, ids, from, to)
  if (cpus.length === 0) return null
  const data = stats.data
  const window = data ? data.to - data.from : range
  const jobs = data?.resources.reduce((s, r) => s + r.jobs, 0) ?? 0
  const busy = data?.resources.reduce((s, r) => s + r.busyMillis, 0) ?? 0
  const usage = window > 0 && ids.length > 0 ? busy / (window * ids.length) : 0
  const items = data?.items ?? []
  const shown = all ? items : items.slice(0, SHOWN)
  const names = new Map(cpus.map((c, i) => [c.resource.ref.key, c.name ?? t('cpu.unnamed', { n: c.number || i + 1 })]))

  return (
    <Section
      title={t('stats.title')}
      aside={<Segmented value={String(range)} options={RANGES.map((r) => ({ value: String(r.ms), label: r.label }))} onChange={(v) => setRange(Number(v))} />}
    >
      <Card className="min-w-0 gap-4 px-4 py-4" data-testid="crafting-stats">
        <div className="grid min-w-0 grid-cols-3 gap-3">
          <Figure label={t('stats.jobs')} value={formatNumber(jobs, locale)} />
          <Figure label={t('stats.busy')} value={formatDuration(busy, locale)} />
          <Figure label={t('stats.usage')} value={`${Math.round(usage * 100)} %`} />
        </div>
        {data && data.resources.length > 1 && (
          <ul className="min-w-0 space-y-2" data-testid="stats-cpus">
            {[...data.resources]
              .sort((a, b) => b.busyMillis - a.busyMillis)
              .map((r) => {
                const share = window > 0 ? r.busyMillis / window : 0
                return (
                  <li key={r.resource} className="min-w-0 space-y-1 text-xs">
                    <div className="flex min-w-0 justify-between gap-2">
                      <span className="min-w-0 truncate">
                        <McText text={names.get(r.resource) ?? r.resource} />
                      </span>
                      <span className="text-muted-foreground tabular shrink-0">
                        {t('stats.cpuLine', { jobs: formatNumber(r.jobs, locale), share: Math.round(share * 100) })}
                      </span>
                    </div>
                    <Progress value={Math.min(100, share * 100)} aria-label={t('stats.usage')} />
                  </li>
                )
              })}
          </ul>
        )}
        {data && items.length === 0 && <p className="text-muted-foreground text-sm">{t('stats.none')}</p>}
        {items.length > 0 && (
          <div className="min-w-0 space-y-1">
            <span className="text-muted-foreground text-xs font-medium">{t('stats.items')}</span>
            <ul className="divide-border min-w-0 divide-y" data-testid="stats-items">
              {shown.map((i) => (
                <li key={i.key} className="flex min-w-0 items-center gap-2 py-2">
                  <ItemIcon stackKey={i.key} name={i.name} />
                  <div className="min-w-0 flex-1">
                    <span className="block truncate text-sm">
                      <McText text={i.name} />
                    </span>
                    <span className="text-muted-foreground tabular block text-xs">
                      {t('stats.itemLine', {
                        amount: i.kind === 'fluid' ? `${formatBuckets(i.amount, locale)} B` : formatSi(i.amount, locale),
                        average: formatDuration(i.busyMillis / Math.max(1, i.jobs), locale),
                      })}
                      {i.longestMillis > 0 && ` · ${t('stats.longest', { time: formatDuration(i.longestMillis, locale) })}`}
                    </span>
                  </div>
                  <span className="tabular shrink-0 text-sm font-semibold" title={t('stats.jobs')}>
                    {formatNumber(i.jobs, locale)}×
                  </span>
                </li>
              ))}
            </ul>
            {items.length > SHOWN && (
              <Button variant="ghost" size="sm" onClick={() => setAll((a) => !a)}>
                {all ? t('cpu.fewer') : t('cpu.allLines', { count: items.length })}
              </Button>
            )}
          </div>
        )}
        <p className="text-muted-foreground text-xs">{t('stats.hint')}</p>
      </Card>
    </Section>
  )
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <span className="text-muted-foreground block truncate text-xs">{label}</span>
      <span className="tabular block truncate text-lg font-semibold">{value}</span>
    </div>
  )
}
