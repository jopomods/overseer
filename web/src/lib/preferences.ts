import { useSyncExternalStore } from 'react'

/**
 * Per-browser display preferences (language, energy unit). Stored in localStorage,
 * which may be unavailable (private mode, blocked storage): every access is guarded
 * and the app works with defaults.
 */
export type Locale = 'de' | 'en'
export type EnergyUnit = 'fe' | 'eu'
/** Chart time axis (ADR-0005): server uptime (offline time collapsed) or calendar time. */
export type TimeAxis = 'uptime' | 'calendar'
/** Energy flow diagram: zones only, or devices and zones. */
export type FlowDetail = 'zones' | 'devices'

export interface Preferences {
  locale: Locale
  energyUnit: EnergyUnit
  axis: TimeAxis
  flowDetail: FlowDetail
  /** Show § colour codes in item and CPU names as colours (default) or hide them. */
  nameColors: boolean
}

const KEY = 'overseer.preferences'

function detectLocale(): Locale {
  const lang = typeof navigator === 'undefined' ? 'en' : navigator.language
  return lang.toLowerCase().startsWith('de') ? 'de' : 'en'
}

function load(): Preferences {
  const defaults: Preferences = { locale: detectLocale(), energyUnit: 'fe', axis: 'uptime', flowDetail: 'zones', nameColors: true }
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return defaults
    const parsed = JSON.parse(raw) as Partial<Preferences>
    return {
      locale: parsed.locale === 'de' || parsed.locale === 'en' ? parsed.locale : defaults.locale,
      energyUnit: parsed.energyUnit === 'eu' ? 'eu' : 'fe',
      axis: parsed.axis === 'calendar' ? 'calendar' : 'uptime',
      flowDetail: parsed.flowDetail === 'devices' ? 'devices' : 'zones',
      nameColors: parsed.nameColors !== false,
    }
  } catch {
    return defaults
  }
}

let current = load()
const listeners = new Set<() => void>()

export function setPreferences(patch: Partial<Preferences>) {
  current = { ...current, ...patch }
  try {
    localStorage.setItem(KEY, JSON.stringify(current))
  } catch {
    // Not persisted; still applies for this page view.
  }
  document.documentElement.lang = current.locale
  listeners.forEach((l) => l())
}

export function usePreferences(): Preferences {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    () => current,
  )
}
