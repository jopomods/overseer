import type { EnergyUnit, Locale } from './preferences'

/** FE (= RF) per EU. Server-wide setting later; GregTech default is 4. */
export const FE_PER_EU = 4

const SI = ['', 'k', 'M', 'G', 'T', 'P', 'E', 'Z']

/** Splits a value into a number and an SI prefix: 1234567 -> ["1.23", "M"]. */
export function siParts(value: number, locale: Locale, digits = 3): [string, string] {
  if (!Number.isFinite(value)) return ['–', '']
  const sign = value < 0 ? '-' : ''
  let v = Math.abs(value)
  let i = 0
  while (v >= 1000 && i < SI.length - 1) {
    v /= 1000
    i++
  }
  const formatted = new Intl.NumberFormat(locale, {
    maximumSignificantDigits: v >= 100 ? Math.max(digits, 3) : digits,
  }).format(v)
  return [`${sign}${formatted}`, SI[i]]
}

/** 1234567 -> "1.23 M" (locale decimal separator). */
export function formatSi(value: number, locale: Locale, digits = 3): string {
  const [number, prefix] = siParts(value, locale, digits)
  return prefix ? `${number} ${prefix}` : number
}

/** Energy amount in the viewer's unit, input in FE. */
export function formatEnergy(fe: number, unit: EnergyUnit, locale: Locale): string {
  const [number, prefix] = siParts(unit === 'eu' ? fe / FE_PER_EU : fe, locale)
  return `${number} ${prefix}${unit === 'eu' ? 'EU' : 'FE'}`
}

/** Energy flow per tick in the viewer's unit, input in FE/t. */
export function formatRate(fePerTick: number, unit: EnergyUnit, locale: Locale): string {
  return `${formatEnergy(fePerTick, unit, locale)}/t`
}

export function formatNumber(value: number, locale: Locale, fractionDigits = 0): string {
  if (!Number.isFinite(value)) return '–'
  return new Intl.NumberFormat(locale, {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  }).format(value)
}

export function formatBytes(bytes: number, locale: Locale): string {
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  let v = bytes
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${formatNumber(v, locale, i === 0 ? 0 : 1)} ${units[i]}`
}

/** Compact duration: "4 s", "12 min", "3 h", "2 d". */
export function formatDuration(ms: number, locale: Locale): string {
  const s = Math.max(0, Math.round(ms / 1000))
  if (s < 60) return `${s} s`
  const m = Math.round(s / 60)
  if (m < 60) return `${m} min`
  const h = Math.round(m / 60)
  if (h < 48) return `${h} h`
  return `${formatNumber(Math.round(h / 24), locale)} ${locale === 'de' ? 'T' : 'd'}`
}

/** Nanoseconds as milliseconds with sensible precision. */
export function formatNanos(nanos: number, locale: Locale): string {
  const ms = nanos / 1_000_000
  return `${formatNumber(ms, locale, ms < 1 ? 2 : 1)} ms`
}
