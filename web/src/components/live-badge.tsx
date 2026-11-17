import type { LiveState } from '@/api/live'
import { formatDuration } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { usePreferences } from '@/lib/preferences'
import { useNow } from '@/lib/use-now'
import { cn } from '@/lib/utils'

/** Live connection state plus data age, e.g. "● Live · data from 2 s ago". */
export function LiveBadge({ state, dataAt }: { state: LiveState; dataAt?: string }) {
  const t = useT()
  const { locale } = usePreferences()
  const now = useNow(1000)
  const age = dataAt ? now - Date.parse(dataAt) : undefined
  const stale = age !== undefined && age > 30_000

  return (
    <span className="text-muted-foreground inline-flex min-w-0 items-center gap-2 text-xs" data-testid="live-badge">
      <span
        className={cn(
          'size-2 shrink-0 rounded-full',
          state === 'live' && !stale ? 'bg-success' : state === 'down' || stale ? 'bg-warning' : 'bg-muted-foreground',
        )}
      />
      <span className="truncate">
        {state === 'down' ? t('common.offline') : t('common.live')}
        {age !== undefined && ` · ${t('common.dataAge', { age: formatDuration(age, locale) })}`}
      </span>
    </span>
  )
}
