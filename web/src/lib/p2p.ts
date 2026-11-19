/** P2P tunnels of an ME network from the grid's live table (docs/modules/ae2.md). */

export interface Ae2Tunnel {
  /** `<dimension>@x,y,z/<side>` */
  id: string
  side: string
  /** Stack key of the tunnel's item, e.g. `item:ae2:me_p2p_tunnel`. */
  item: string
  name: string
  /** Unsigned; 0 = not linked. */
  frequency: number
  hex: string
  /** AE2's four colours of the frequency as CSS colours. */
  colors: string[]
  output: boolean
  target: string
  targetBlock: string
  active: boolean
}

export const TUNNEL_TYPES = ['me', 'item', 'fluid', 'fe', 'redstone', 'light', 'other'] as const
export type TunnelType = (typeof TUNNEL_TYPES)[number]

export type LinkProblem = 'unlinked' | 'no_input' | 'no_output' | 'inactive'

/** Tunnels of one type and frequency: one input, any number of outputs. Unlinked tunnels stand alone. */
export interface P2pLink {
  key: string
  type: TunnelType
  item: string
  frequency: number
  hex: string
  colors: string[]
  input?: Ae2Tunnel
  outputs: Ae2Tunnel[]
  problem?: LinkProblem
}

export function tunnelsOf(rows: Record<string, string>[] | undefined): Ae2Tunnel[] {
  return (rows ?? [])
    .filter((r) => r.row === 'p2p' && r.id)
    .map((r) => {
      const slash = r.id.indexOf('/', r.id.indexOf('@'))
      return {
        id: r.id,
        side: slash < 0 ? '' : r.id.slice(slash + 1),
        item: r.item ?? '',
        name: r.name ?? '',
        frequency: Number(r.frequency) || 0,
        hex: r.hex ?? '',
        colors: (r.colors ?? '').split(',').filter((c) => /^[0-9a-f]{6}$/i.test(c)).map((c) => `#${c}`),
        output: r.output === 'true',
        target: r.target ?? '',
        targetBlock: r.targetBlock ?? '',
        active: r.active === 'true',
      }
    })
}

/** "item:ae2:me_p2p_tunnel" -> "me"; add-on tunnels are "other". */
export function tunnelType(item: string): TunnelType {
  const path = item.slice(item.lastIndexOf(':') + 1)
  const name = path.replace(/_p2p_tunnel$/, '')
  return (TUNNEL_TYPES as readonly string[]).includes(name) && name !== path ? (name as TunnelType) : 'other'
}

const PROBLEM_ORDER: Record<LinkProblem, number> = { no_input: 0, no_output: 1, unlinked: 2, inactive: 3 }

/** Groups tunnels into links; links with problems first, then by type and frequency. */
export function p2pLinks(tunnels: Ae2Tunnel[]): P2pLink[] {
  const links = new Map<string, P2pLink>()
  for (const t of tunnels) {
    const key = t.frequency === 0 ? `0|${t.id}` : `${t.item}|${t.frequency}`
    let link = links.get(key)
    if (!link) {
      link = { key, type: tunnelType(t.item), item: t.item, frequency: t.frequency, hex: t.hex, colors: t.colors, outputs: [] }
      links.set(key, link)
    }
    if (t.output) link.outputs.push(t)
    else link.input ??= t
  }
  for (const link of links.values()) link.problem = problemOf(link)
  return [...links.values()].sort(
    (a, b) =>
      (a.problem ? PROBLEM_ORDER[a.problem] : 9) - (b.problem ? PROBLEM_ORDER[b.problem] : 9) ||
      TUNNEL_TYPES.indexOf(a.type) - TUNNEL_TYPES.indexOf(b.type) ||
      a.frequency - b.frequency ||
      a.key.localeCompare(b.key),
  )
}

function problemOf(link: P2pLink): LinkProblem | undefined {
  if (link.frequency === 0) return 'unlinked'
  if (!link.input) return 'no_input'
  if (link.outputs.length === 0) return 'no_output'
  if (!link.input.active || link.outputs.some((o) => !o.active)) return 'inactive'
  return undefined
}
