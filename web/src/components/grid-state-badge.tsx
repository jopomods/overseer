import { Badge } from '@/components/ui/badge'
import type { GridState } from '@/lib/ae2'
import { useT } from '@/lib/i18n'

/** Online, booting, no power or controller conflict. */
export function GridStateBadge({ state }: { state: GridState }) {
  const t = useT()
  const variant = state === 'online' ? 'secondary' : state === 'booting' ? 'outline' : 'destructive'
  return (
    <Badge variant={variant} className="shrink-0">
      {t(`ae2.state.${state}`)}
    </Badge>
  )
}
