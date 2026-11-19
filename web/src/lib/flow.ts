/**
 * Flows of an ME network (ADR-0020): what each device put into or took out of the network, per
 * item, over a window. Pure helpers over the rows of `GET /api/v1/flows`.
 */
export interface FlowRow {
  device: string
  deviceKind: string
  deviceName: string
  key: string
  kind: string
  name: string
  inserted: number
  extracted: number
}

export interface DeviceAmount {
  device: string
  deviceKind: string
  deviceName: string
  amount: number
}

export interface ItemFlow {
  key: string
  kind: string
  name: string
  inserted: number
  extracted: number
  /** Devices that put it in, most first. */
  sources: DeviceAmount[]
  /** Devices that took it out, most first. */
  sinks: DeviceAmount[]
}

export interface DeviceFlow {
  device: string
  deviceKind: string
  deviceName: string
  items: Array<{ key: string; kind: string; name: string; inserted: number; extracted: number }>
  /** Activity for sorting: items count one, fluids one per bucket. */
  weight: number
}

/** Fluids move in mB; a bucket weighs like an item when ranking. */
function weight(kind: string, amount: number): number {
  return kind === 'fluid' ? amount / 1000 : amount
}

/** Per item: totals and the devices on both sides, busiest items first. */
export function byItem(rows: FlowRow[]): ItemFlow[] {
  const map = new Map<string, ItemFlow>()
  for (const r of rows) {
    let item = map.get(r.key)
    if (!item) {
      item = { key: r.key, kind: r.kind, name: r.name, inserted: 0, extracted: 0, sources: [], sinks: [] }
      map.set(r.key, item)
    }
    item.inserted += r.inserted
    item.extracted += r.extracted
    const device = { device: r.device, deviceKind: r.deviceKind, deviceName: r.deviceName }
    if (r.inserted > 0) item.sources.push({ ...device, amount: r.inserted })
    if (r.extracted > 0) item.sinks.push({ ...device, amount: r.extracted })
  }
  for (const item of map.values()) {
    item.sources.sort((a, b) => b.amount - a.amount)
    item.sinks.sort((a, b) => b.amount - a.amount)
  }
  return [...map.values()].sort(
    (a, b) => weight(b.kind, b.inserted + b.extracted) - weight(a.kind, a.inserted + a.extracted) || a.name.localeCompare(b.name),
  )
}

/** Per device: what it moved of each item, busiest devices first. */
export function byDevice(rows: FlowRow[]): DeviceFlow[] {
  const map = new Map<string, DeviceFlow>()
  for (const r of rows) {
    let device = map.get(r.device)
    if (!device) {
      device = { device: r.device, deviceKind: r.deviceKind, deviceName: r.deviceName, items: [], weight: 0 }
      map.set(r.device, device)
    }
    device.items.push({ key: r.key, kind: r.kind, name: r.name, inserted: r.inserted, extracted: r.extracted })
    device.weight += weight(r.kind, r.inserted + r.extracted)
  }
  for (const d of map.values()) {
    d.items.sort((a, b) => weight(b.kind, b.inserted + b.extracted) - weight(a.kind, a.inserted + a.extracted))
  }
  return [...map.values()].sort((a, b) => b.weight - a.weight || a.deviceName.localeCompare(b.deviceName))
}

/** Bus ids that moved nothing in the window. */
export function idle(busIds: string[], rows: FlowRow[]): Set<string> {
  const active = new Set(rows.map((r) => r.device))
  return new Set(busIds.filter((id) => !active.has(id)))
}

/** Device kinds the dashboard names; others show as they come. */
export const DEVICE_KINDS = [
  'import_bus',
  'export_bus',
  'storage_bus',
  'interface',
  'pattern_provider',
  'terminal',
  'crafting',
  'player',
  'other',
] as const
