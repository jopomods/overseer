import { useState } from 'react'
import { Section } from '@/components/stat'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Progress } from '@/components/ui/progress'
import { idPos, type Ae2Drive, type CellState } from '@/lib/ae2'
import { formatBytes, formatNumber } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { usePreferences } from '@/lib/preferences'
import { cn } from '@/lib/utils'

const SHOWN = 6

/** AE2's own cell colours: green empty, blue in use, orange types full, red full. */
const CELL_COLOR: Record<CellState, string> = {
  empty: 'bg-success',
  not_empty: 'bg-[var(--chart-2)]',
  types_full: 'bg-warning',
  full: 'bg-destructive',
}

/** Drives and ME chests of a network: total fill, then each drive with its cells, fullest first. */
export function DrivesSection({ drives }: { drives: Ae2Drive[] }) {
  const t = useT()
  const { locale } = usePreferences()
  const [all, setAll] = useState(false)
  if (drives.length === 0) return null
  const used = drives.reduce((s, d) => s + d.usedBytes, 0)
  const total = drives.reduce((s, d) => s + d.totalBytes, 0)
  const typesUsed = drives.reduce((s, d) => s + d.usedTypes, 0)
  const typesTotal = drives.reduce((s, d) => s + d.totalTypes, 0)
  const cells = drives.flatMap((d) => d.cells)
  const full = cells.filter((c) => c.state === 'full' || c.state === 'types_full').length
  const sorted = [...drives].sort((a, b) => fill(b) - fill(a) || a.id.localeCompare(b.id, undefined, { numeric: true }))
  const shown = all ? sorted : sorted.slice(0, SHOWN)

  return (
    <Section
      title={t('drive.title')}
      aside={
        <span className="text-muted-foreground shrink-0 text-xs">
          {t('drive.summary', { drives: drives.length, cells: cells.length })}
        </span>
      }
    >
      <Card className="min-w-0 gap-3 px-4 py-4" data-testid="drive-summary">
        <div className="flex min-w-0 flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
          <span className="tabular text-lg font-semibold">
            {formatBytes(used, locale)} <span className="text-muted-foreground text-sm font-normal">/ {formatBytes(total, locale)}</span>
          </span>
          <span className="text-muted-foreground tabular text-xs">
            {t('drive.types', { used: formatNumber(typesUsed, locale), total: formatNumber(typesTotal, locale) })}
          </span>
        </div>
        <Progress value={total > 0 ? (used / total) * 100 : 0} aria-label={t('drive.bytes')} />
        {full > 0 && <p className="text-warning text-xs">{t('drive.fullCells', { count: full })}</p>}
      </Card>

      <ul className="mt-3 grid min-w-0 gap-3 md:grid-cols-2 xl:grid-cols-3" data-testid="drive-list">
        {shown.map((d) => (
          <li key={d.id} className="min-w-0">
            <Card className="min-w-0 gap-2 px-4 py-3">
              <div className="flex min-w-0 items-baseline justify-between gap-2">
                <span className="min-w-0 truncate text-sm font-medium">
                  {t(d.block === 'ae2:chest' ? 'drive.chest' : 'drive.drive')} · {idPos(d.id)}
                </span>
                <span className="text-muted-foreground tabular shrink-0 text-xs">
                  {d.totalBytes > 0 ? `${formatNumber(fill(d) * 100, locale, 0)} %` : '–'}
                </span>
              </div>
              <div className="flex min-w-0 flex-wrap gap-1" aria-label={t('drive.cells')}>
                {d.cells.map((c) => (
                  <span
                    key={c.slot}
                    className={cn('h-3 w-5 rounded-sm', CELL_COLOR[c.state])}
                    title={`${c.item} · ${t(`drive.state.${c.state}`)}`}
                  />
                ))}
                {d.cells.length === 0 && <span className="text-muted-foreground text-xs">{t('drive.noCells')}</span>}
              </div>
              {d.totalBytes > 0 && (
                <span className="text-muted-foreground tabular text-xs">
                  {formatBytes(d.usedBytes, locale)} / {formatBytes(d.totalBytes, locale)} ·{' '}
                  {t('drive.types', { used: formatNumber(d.usedTypes, locale), total: formatNumber(d.totalTypes, locale) })}
                </span>
              )}
            </Card>
          </li>
        ))}
      </ul>
      {drives.length > SHOWN && (
        <Button variant="ghost" size="sm" className="mt-2" onClick={() => setAll((a) => !a)}>
          {all ? t('cpu.fewer') : t('cpu.allLines', { count: drives.length })}
        </Button>
      )}
    </Section>
  )
}

function fill(d: Ae2Drive) {
  return d.totalBytes > 0 ? d.usedBytes / d.totalBytes : 0
}
