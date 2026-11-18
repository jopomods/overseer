import type { History, Sessions } from '@/api/hooks'
import { formatDuration } from './format'
import type { Locale } from './preferences'

/** Shared helpers for history charts (ADR-0005, ADR-0009). */
export const RANGES = [
  { ms: 3_600_000, label: '1 h' },
  { ms: 6 * 3_600_000, label: '6 h' },
  { ms: 24 * 3_600_000, label: '24 h' },
  { ms: 7 * 24 * 3_600_000, label: '7 d' },
  { ms: 30 * 24 * 3_600_000, label: '30 d' },
]

export interface Marker {
  session: number
  uptime: number
  offlineMs: number
  wallFrom: number
  wallTo: number
}

/** One marker per restart inside the charted range. */
export function offlineMarkers(sessions: Sessions | undefined, history: History | undefined): Marker[] {
  if (!sessions || !history) return []
  const markers: Marker[] = []
  for (let i = 1; i < sessions.sessions.length; i++) {
    const previous = sessions.sessions[i - 1]
    const current = sessions.sessions[i]
    if (current.startedUptime < history.from || current.startedUptime > history.to) continue
    const wallFrom = Date.parse(previous.lastAt)
    const wallTo = Date.parse(current.startedAt)
    markers.push({ session: current.session, uptime: current.startedUptime, offlineMs: Math.max(0, wallTo - wallFrom), wallFrom, wallTo })
  }
  return markers
}

/** Uptime axis labels relative to now: "-6 h", "-30 min", "now". */
export function relativeLabel(deltaMs: number, locale: Locale): string {
  if (Math.abs(deltaMs) < 30_000) return locale === 'de' ? 'jetzt' : 'now'
  const minutes = -deltaMs / 60_000
  // Below 10 h one decimal, so neighbouring ticks (e.g. -80 and -60 min) stay distinct.
  if (minutes >= 60 && minutes < 600) {
    const hours = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(minutes / 60)
    return `-${hours} h`
  }
  return `-${formatDuration(-deltaMs, locale)}`
}

export function wallLabel(wall: number, rangeMs: number, locale: Locale): string {
  const date = new Date(wall)
  return rangeMs <= 24 * 3_600_000
    ? date.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString(locale, { day: '2-digit', month: '2-digit' })
}
