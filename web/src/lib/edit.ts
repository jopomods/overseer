import { ActionFailed } from '@/api/hooks'
import type { Translate } from './i18n'

/** Shared by the world action forms (ADR-0016). */

/** Native select styled like the inputs. */
export const SELECT =
  'border-input dark:bg-input/30 h-8 min-w-0 rounded-lg border bg-transparent px-2 text-base md:text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50'

export function errorText(t: Translate, error: unknown): string {
  if (error instanceof ActionFailed) {
    if (error.status === 429) return t('edit.rateLimited')
    if (error.status === 503) return t('edit.busy')
    return error.message
  }
  return t('edit.failed')
}
