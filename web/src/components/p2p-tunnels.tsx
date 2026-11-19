import { ArrowRight } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Segmented } from '@/components/history-chart'
import { ItemIcon } from '@/components/item-icon'
import { McText } from '@/components/mc-text'
import { Section } from '@/components/stat'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { idPos } from '@/lib/ae2'
import { useT, type MessageKey } from '@/lib/i18n'
import { p2pLinks, TUNNEL_TYPES, type Ae2Tunnel, type P2pLink, type TunnelType } from '@/lib/p2p'
import { cn } from '@/lib/utils'

const SHOWN = 12

/**
 * P2P tunnels of an ME network grouped into links (one input, its outputs), problems first:
 * unlinked tunnels, links without input or output, tunnels without power or channel.
 */
export function P2pSection({ tunnels }: { tunnels: Ae2Tunnel[] }) {
  const t = useT()
  const [type, setType] = useState<TunnelType | 'all'>('all')
  const [onlyProblems, setOnlyProblems] = useState<'all' | 'problems'>('all')
  const [all, setAll] = useState(false)
  const links = useMemo(() => p2pLinks(tunnels), [tunnels])
  if (tunnels.length === 0) return null
  const types = TUNNEL_TYPES.filter((ty) => links.some((l) => l.type === ty))
  const problems = links.filter((l) => l.problem).length
  const list = links.filter((l) => (type === 'all' || l.type === type) && (onlyProblems === 'all' || l.problem))
  const shown = all ? list : list.slice(0, SHOWN)

  return (
    <Section
      title={t('p2p.title')}
      aside={
        <span className={cn('shrink-0 text-xs', problems > 0 ? 'text-warning font-medium' : 'text-muted-foreground')}>
          {problems > 0 ? t('p2p.problems', { count: problems }) : t('p2p.summary', { links: links.length, tunnels: tunnels.length })}
        </span>
      }
    >
      <p className="text-muted-foreground mb-2 text-sm">{t('p2p.hint')}</p>
      <div className="mb-3 flex min-w-0 flex-wrap items-center gap-2" data-testid="p2p-toolbar">
        {types.length > 1 && (
          <Segmented<TunnelType | 'all'>
            value={type}
            onChange={setType}
            options={[{ value: 'all', label: t('p2p.type.all') }, ...types.map((ty) => ({ value: ty, label: t(`p2p.type.${ty}` as MessageKey) }))]}
          />
        )}
        {problems > 0 && (
          <Segmented<'all' | 'problems'>
            value={onlyProblems}
            onChange={setOnlyProblems}
            options={[
              { value: 'all', label: t('p2p.everything') },
              { value: 'problems', label: t('p2p.onlyProblems') },
            ]}
          />
        )}
      </div>
      {list.length === 0 && <p className="text-muted-foreground text-sm">{t('p2p.none')}</p>}
      <ul className="grid min-w-0 items-start gap-3 md:grid-cols-2 xl:grid-cols-3" data-testid="p2p-list">
        {shown.map((l) => (
          <LinkCard key={l.key} link={l} />
        ))}
      </ul>
      {list.length > SHOWN && (
        <Button variant="ghost" size="sm" className="mt-2" onClick={() => setAll((a) => !a)}>
          {all ? t('cpu.fewer') : t('cpu.allLines', { count: list.length })}
        </Button>
      )}
    </Section>
  )
}

function LinkCard({ link }: { link: P2pLink }) {
  const t = useT()
  const typeName = t(`p2p.type.${link.type}` as MessageKey)
  return (
    <li className="min-w-0">
      <Card className={cn('min-w-0 gap-2 px-4 py-3', link.problem && link.problem !== 'inactive' && 'border-warning/50')}>
        <div className="flex min-w-0 items-center gap-2">
          <ItemIcon stackKey={link.item} name={typeName} />
          <span className="min-w-0 flex-1 truncate text-sm font-medium">{typeName}</span>
          {link.frequency !== 0 && <Frequency hex={link.hex} colors={link.colors} />}
        </div>
        {link.problem && (
          <Badge variant="outline" className={cn('self-start', link.problem !== 'inactive' && 'text-warning border-warning/50')}>
            {t(`p2p.problem.${link.problem}` as MessageKey)}
          </Badge>
        )}
        <ul className="min-w-0 space-y-1 text-xs">
          {link.input && <TunnelLine tunnel={link.input} />}
          {link.outputs.map((o) => (
            <TunnelLine key={o.id} tunnel={o} />
          ))}
        </ul>
      </Card>
    </li>
  )
}

/** AE2 shows a frequency as four colours on the tunnel and as hex on the memory card. */
function Frequency({ hex, colors }: { hex: string; colors: string[] }) {
  const t = useT()
  return (
    <span className="flex shrink-0 items-center gap-1.5" title={t('p2p.frequency', { hex })}>
      <span className="flex overflow-hidden rounded-sm border" aria-hidden>
        {colors.map((c, i) => (
          <span key={i} className="size-2.5" style={{ backgroundColor: c }} />
        ))}
      </span>
      <span className="text-muted-foreground font-mono text-xs">{hex}</span>
    </span>
  )
}

function TunnelLine({ tunnel }: { tunnel: Ae2Tunnel }) {
  const t = useT()
  const pos = idPos(tunnel.side ? tunnel.id.slice(0, tunnel.id.lastIndexOf('/')) : tunnel.id)
  const label = tunnel.name || tunnel.target
  return (
    <li className={cn('flex min-w-0 items-center gap-1.5', !tunnel.active && 'text-muted-foreground')}>
      <span
        className={cn('shrink-0 rounded px-1 text-[10px] font-semibold uppercase', tunnel.output ? 'bg-muted' : 'bg-primary/15 text-primary')}
      >
        {tunnel.output ? t('p2p.output') : t('p2p.input')}
      </span>
      {tunnel.output && <ArrowRight className="text-muted-foreground size-3 shrink-0" aria-hidden />}
      <span className="min-w-0 flex-1 truncate">{label ? <McText text={label} /> : '–'}</span>
      <span className="text-muted-foreground tabular shrink-0">
        {pos}
        {tunnel.side && ` · ${t(`provider.side.${tunnel.side}` as MessageKey)}`}
      </span>
    </li>
  )
}
