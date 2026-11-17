/** Traffic-light tone for TPS: 20 is perfect, below 15 is bad. */
export function tpsTone(tps: number | undefined): 'good' | 'warn' | 'bad' | undefined {
  if (tps === undefined) return undefined
  return tps >= 19.5 ? 'good' : tps >= 15 ? 'warn' : 'bad'
}
