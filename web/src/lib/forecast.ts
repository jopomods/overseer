/**
 * Simple energy forecast from a short history (least-squares slope over time).
 * Returns the milliseconds until the stored energy reaches zero, or a trend label.
 */
export type Forecast =
  | { kind: 'emptying'; msUntilEmpty: number }
  | { kind: 'charging' }
  | { kind: 'stable' }
  | { kind: 'unknown' }

/** Changes smaller than this share of the current level per hour count as stable. */
const STABLE_PER_HOUR = 0.01

export function forecastEmpty(points: Array<{ t: number; value: number }>): Forecast {
  if (points.length < 3) return { kind: 'unknown' }
  const n = points.length
  const meanT = points.reduce((s, p) => s + p.t, 0) / n
  const meanV = points.reduce((s, p) => s + p.value, 0) / n
  let num = 0
  let den = 0
  for (const p of points) {
    num += (p.t - meanT) * (p.value - meanV)
    den += (p.t - meanT) ** 2
  }
  if (den === 0) return { kind: 'unknown' }
  const slopePerMs = num / den
  const current = points[n - 1].value
  const perHour = slopePerMs * 3_600_000
  if (Math.abs(perHour) <= Math.max(1, Math.abs(current) * STABLE_PER_HOUR)) return { kind: 'stable' }
  if (slopePerMs > 0) return { kind: 'charging' }
  return current <= 0 ? { kind: 'emptying', msUntilEmpty: 0 } : { kind: 'emptying', msUntilEmpty: current / -slopePerMs }
}
