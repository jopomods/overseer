import type { Resource, Snapshot } from '@/api/hooks'

/** Resources of one type, e.g. `player` or `dimension`. */
export function ofType(snapshot: Snapshot | undefined, type: string): Resource[] {
  return snapshot?.resources.filter((r) => r.ref.type === type) ?? []
}

export function find(snapshot: Snapshot | undefined, type: string, id: string): Resource | undefined {
  return snapshot?.resources.find((r) => r.ref.type === type && r.ref.id === id)
}

export function metric(resource: Resource | undefined, key: string): number | undefined {
  const value = resource?.metrics[key]
  return typeof value === 'number' ? value : undefined
}

/** Milliseconds since the snapshot was taken (wall clock). */
export function ageMs(snapshot: Snapshot | undefined, now: number): number | undefined {
  return snapshot ? Math.max(0, now - Date.parse(snapshot.at.wall)) : undefined
}
