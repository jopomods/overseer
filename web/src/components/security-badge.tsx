import { Badge } from '@/components/ui/badge'
import { useT, type MessageKey } from '@/lib/i18n'

/** Flux network security level: public, encrypted (password) or private. */
export function SecurityBadge({ security }: { security: string }) {
  const t = useT()
  const key = `energy.security.${security}` as MessageKey
  return <Badge variant="secondary">{t(key)}</Badge>
}
