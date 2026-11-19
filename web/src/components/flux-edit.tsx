import { Crown, History, Trash2, UserPlus } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useActionLog, useRunAction, type ActionLogEntry } from '@/api/hooks'
import { Actions, Counter, Feedback, Field } from '@/components/edit-form'
import { errorText, SELECT } from '@/lib/edit'
import { McText } from '@/components/mc-text'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { tierInName, withTier, type Tier } from '@/lib/tiers'
import {
  FLUX_ACTIONS,
  FLUX_COLORS,
  MAX_NAME,
  MAX_PASSWORD,
  MAX_PRIORITY,
  MIN_PRIORITY,
  nameHints,
  namesFromPattern,
  parseHexColor,
  type FluxDevice,
  type FluxMember,
  type FluxNetwork,
} from '@/lib/flux'
import { useT, type MessageKey, type Translate } from '@/lib/i18n'
import { plainText } from '@/lib/mc-text'
import { usePreferences } from '@/lib/preferences'
import { cn } from '@/lib/utils'

// --- network ---------------------------------------------------------------------------

/** Name, colour (Flux's presets or any hex code), security and password of a network. */
export function NetworkSettings({ network, onDone }: { network: FluxNetwork; onDone: () => void }) {
  const t = useT()
  const run = useRunAction('flux')
  const [name, setName] = useState(network.name)
  const [hex, setHex] = useState(network.color)
  const [security, setSecurity] = useState(network.security)
  const [password, setPassword] = useState('')
  const color = parseHexColor(hex)
  const nameError = name.trim() === '' || name.length > MAX_NAME
  const needsPassword = security === 'encrypted' && network.security !== 'encrypted' && password === ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const params: Record<string, unknown> = {}
    if (name !== network.name) params.name = name
    if (color && color !== network.color) params.color = color
    if (security !== network.security) params.security = security
    if (security === 'encrypted' && password) params.password = password
    if (Object.keys(params).length === 0) return onDone()
    run.mutate({ type: FLUX_ACTIONS.networkEdit, resource: network.resource.ref.key, params }, { onSuccess: onDone })
  }

  return (
    <Card className="mb-5 min-w-0 py-4" data-testid="network-settings">
      <CardContent className="min-w-0 px-4">
        <form onSubmit={submit} className="min-w-0 space-y-4">
          <Field label={t('edit.name')} hint={<Counter value={name} max={MAX_NAME} />}>
            <Input value={name} onChange={(e) => setName(e.target.value)} aria-invalid={nameError} autoFocus />
          </Field>
          {name.includes('§') && (
            <p className="text-sm">
              <span className="text-muted-foreground text-xs">{t('edit.preview')}: </span>
              <McText text={name} />
            </p>
          )}

          <div className="min-w-0 space-y-2">
            <span className="text-muted-foreground block text-xs font-medium">{t('edit.color')}</span>
            <div className="flex flex-wrap gap-1.5" role="radiogroup" aria-label={t('edit.color')}>
              {FLUX_COLORS.map((c) => (
                <button
                  key={c}
                  type="button"
                  role="radio"
                  aria-checked={color === c}
                  aria-label={c}
                  title={c}
                  onClick={() => setHex(c)}
                  className={cn(
                    'size-7 shrink-0 rounded-full border-2 transition-transform',
                    color === c ? 'border-foreground scale-110' : 'border-transparent',
                  )}
                  style={{ backgroundColor: c }}
                />
              ))}
            </div>
            <div className="flex min-w-0 items-center gap-2">
              <input
                type="color"
                value={color ?? '#000000'}
                onChange={(e) => setHex(e.target.value)}
                className="h-8 w-10 shrink-0 cursor-pointer rounded border-0 bg-transparent p-0"
                aria-label={t('edit.colorPicker')}
              />
              <Input
                value={hex}
                onChange={(e) => setHex(e.target.value)}
                className="w-32"
                aria-invalid={!color}
                aria-label={t('edit.hex')}
                placeholder="#ff7a00"
              />
            </div>
          </div>

          <div className="min-w-0 space-y-2">
            <span className="text-muted-foreground block text-xs font-medium">{t('edit.security')}</span>
            <Segmented
              value={security}
              onChange={setSecurity}
              options={(['public', 'encrypted', 'private'] as const).map((s) => ({
                value: s,
                label: t(`energy.security.${s}` as MessageKey),
              }))}
            />
            {security === 'encrypted' && (
              <Field
                label={t('edit.password')}
                hint={<Counter value={password} max={MAX_PASSWORD} />}
              >
                <Input
                  type="password"
                  autoComplete="new-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder={network.security === 'encrypted' ? t('edit.passwordKeep') : ''}
                  aria-invalid={password.length > MAX_PASSWORD || needsPassword}
                />
              </Field>
            )}
          </div>

          <Feedback error={run.error ? errorText(t, run.error) : undefined} />
          <Actions
            pending={run.isPending}
            onCancel={onDone}
            disabled={nameError || !color || password.length > MAX_PASSWORD || needsPassword}
          />
        </form>
      </CardContent>
    </Card>
  )
}

function Segmented<T extends string>({
  value,
  onChange,
  options,
}: {
  value: T
  onChange: (value: T) => void
  options: Array<{ value: T; label: string }>
}) {
  return (
    <div className="bg-muted inline-flex max-w-full min-w-0 flex-wrap gap-1 rounded-lg p-1" role="radiogroup">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={value === o.value}
          onClick={() => onChange(o.value)}
          className={cn(
            'shrink-0 rounded-md px-2.5 py-1 text-xs font-medium whitespace-nowrap transition-colors',
            value === o.value ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

// --- devices ---------------------------------------------------------------------------

type Tri = 'keep' | 'on' | 'off'

function triOf(values: boolean[]): Tri {
  return values.every((v) => v) ? 'on' : values.every((v) => !v) ? 'off' : 'keep'
}

function TriField({ label, value, onChange, mixed }: { label: string; value: Tri; onChange: (v: Tri) => void; mixed: boolean }) {
  const t = useT()
  const options: Array<{ value: Tri; label: string }> = [
    ...(mixed ? [{ value: 'keep' as const, label: t('edit.keep') }] : []),
    { value: 'on', label: t('edit.on') },
    { value: 'off', label: t('edit.off') },
  ]
  return (
    <div className="min-w-0 space-y-1">
      <span className="text-muted-foreground block truncate text-xs font-medium">{label}</span>
      <Segmented value={value} onChange={onChange} options={options} />
    </div>
  )
}

/**
 * Settings of one or several devices. With several, empty fields and "keep" leave each device
 * as it is, and the name is a pattern ({n} = number in the selection, {name} = current name).
 */
export function DeviceEditor({
  networkKey,
  devices,
  targets,
  tiers = [],
  zoneNames = [],
  otherNames = [],
  onDone,
}: {
  networkKey: string
  devices: FluxDevice[]
  /** Networks the viewer may move devices to. */
  targets: FluxNetwork[]
  /** Voltage tiers the server uses; empty hides the tier field. */
  tiers?: Tier[]
  /** Zones already used in the network, offered as name prefixes. */
  zoneNames?: string[]
  /** Names of the other devices in the network (duplicate hint). */
  otherNames?: string[]
  onDone: () => void
}) {
  const t = useT()
  const run = useRunAction('flux')
  const single = devices.length === 1 ? devices[0] : undefined
  const [name, setName] = useState(single?.name ?? '')
  const [priority, setPriority] = useState(single ? String(single.priority) : '')
  const [limit, setLimit] = useState(single ? String(single.limit) : '')
  const [disableLimit, setDisableLimit] = useState<Tri>(triOf(devices.map((d) => d.disableLimit)))
  const [surge, setSurge] = useState<Tri>(triOf(devices.map((d) => d.surge)))
  const [chunkLoading, setChunkLoading] = useState<Tri>(triOf(devices.map((d) => d.chunkLoading)))
  const [target, setTarget] = useState('')
  const [message, setMessage] = useState<string>()
  const [error, setError] = useState<string>()
  /** Several devices: 'keep', 'none' or a tier name, applied to the (new) names. */
  const [tierChoice, setTierChoice] = useState('keep')
  const mixed = devices.length > 1
  const tierByName = (n: string) => tiers.find((x) => x.name === n) ?? null

  const patterned = name === '' ? devices.map((d) => d.name) : namesFromPattern(name, devices.map((d) => d.name))
  const names = single
    ? [name]
    : name === '' && tierChoice === 'keep'
      ? []
      : tierChoice === 'keep'
        ? patterned
        : patterned.map((n) => withTier(n, tierByName(tierChoice), tiers))
  const hints = single ? nameHints(name, otherNames) : []
  const tooLong = names.some((n) => n.length > MAX_NAME)
  const priorityValue = priority.trim() === '' ? undefined : Number(priority)
  const priorityBad =
    priorityValue !== undefined && (!Number.isInteger(priorityValue) || priorityValue < MIN_PRIORITY || priorityValue > MAX_PRIORITY)
  const limitValue = limit.trim() === '' ? undefined : Number(limit)
  const limitBad = limitValue !== undefined && (!Number.isInteger(limitValue) || limitValue < 0)
  const unloaded = devices.filter((d) => !d.loaded).length
  const storages = devices.some((d) => d.type === 'storage')

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setError(undefined)
    setMessage(undefined)
    const edits = devices
      .map((d, i) => {
        const e: Record<string, unknown> = { id: d.id }
        const newName = single ? name : names[i]
        if (newName !== undefined && newName !== d.name) e.name = newName
        if (priorityValue !== undefined && priorityValue !== d.priority) e.priority = priorityValue
        if (limitValue !== undefined && limitValue !== d.limit) e.limit = limitValue
        const tri = (v: Tri, current: boolean, key: string) => {
          if (v !== 'keep' && (v === 'on') !== current) e[key] = v === 'on'
        }
        tri(disableLimit, d.disableLimit, 'disableLimit')
        tri(surge, d.surge, 'surge')
        if (d.type !== 'storage') tri(chunkLoading, d.chunkLoading, 'chunkLoading')
        return e
      })
      .filter((e) => Object.keys(e).length > 1)
    try {
      const notes: string[] = []
      if (edits.length > 0) {
        const result = await run.mutateAsync({ type: FLUX_ACTIONS.deviceEdit, resource: networkKey, params: { devices: edits } })
        if (result.message) notes.push(result.message)
      }
      if (target) {
        const result = await run.mutateAsync({
          type: FLUX_ACTIONS.deviceConnect,
          resource: target,
          params: { devices: devices.map((d) => d.id) },
        })
        if (result.message) notes.push(result.message)
      }
      if (notes.length) setMessage(notes.join(' '))
      else onDone()
    } catch (e) {
      setError(errorText(t, e))
    }
  }

  return (
    <li className="min-w-0 px-4 py-3" data-testid="device-editor">
      <p className="text-muted-foreground mb-3 truncate text-xs">
        {single ? (
          <>
            {t('edit.device')}: <span className="text-foreground font-medium"><McText text={single.label} /></span>
          </>
        ) : (
          t('edit.devicesSelected', { count: devices.length })
        )}
      </p>
      <form onSubmit={submit} className="min-w-0 space-y-3">
        <Field
          label={single ? t('edit.name') : t('edit.namePattern')}
          hint={single ? <Counter value={name} max={MAX_NAME} /> : undefined}
        >
          <Input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={single ? t('edit.nameEmpty') : t('edit.namePatternPlaceholder')}
            aria-invalid={tooLong}
            list={`zones-${networkKey}`}
          />
        </Field>
        <datalist id={`zones-${networkKey}`}>
          {zoneNames.map((z) => (
            <option key={z} value={`${z}: `} />
          ))}
        </datalist>
        <p className="text-muted-foreground text-xs" data-testid="naming-hint">
          {t(tiers.length ? 'naming.recommendedTier' : 'naming.recommended')}
          {hints.includes('noZone') && ` · ${t('naming.noZone')}`}
          {hints.includes('duplicate') && <span className="text-warning"> · {t('naming.duplicate')}</span>}
        </p>
        {tiers.length > 0 && (
          <Field label={t('tier.label')}>
            <select
              className={cn(SELECT, 'w-full sm:w-48')}
              value={single ? (tierInName(name, tiers)?.name ?? 'none') : tierChoice}
              onChange={(e) =>
                single ? setName(withTier(name, tierByName(e.target.value), tiers)) : setTierChoice(e.target.value)
              }
            >
              {mixed && <option value="keep">{t('edit.keep')}</option>}
              <option value="none">{t('tier.none')}</option>
              {tiers.map((x) => (
                <option key={x.name} value={x.name}>
                  {x.name}
                </option>
              ))}
            </select>
          </Field>
        )}
        {mixed && names.length > 0 && (
          <p className="text-muted-foreground truncate text-xs" data-testid="name-preview">
            {t('edit.preview')}: {names.slice(0, 3).map((n) => plainText(n)).join(', ')}
            {names.length > 3 && ', …'}
          </p>
        )}
        {mixed && names.length === 0 && <p className="text-muted-foreground text-xs">{t('edit.namePatternHint')}</p>}
        {tooLong && <p className="text-destructive text-xs">{t('edit.nameTooLong', { max: MAX_NAME })}</p>}

        <div className="grid min-w-0 gap-3 sm:grid-cols-2">
          <Field label={t('energy.priority')} hint={`${MIN_PRIORITY} … ${MAX_PRIORITY}`}>
            <Input
              inputMode="numeric"
              value={priority}
              onChange={(e) => setPriority(e.target.value)}
              placeholder={mixed ? t('edit.keep') : undefined}
              aria-invalid={priorityBad}
            />
          </Field>
          <Field label={t('edit.limit')} hint="FE/t">
            <Input
              inputMode="numeric"
              value={limit}
              onChange={(e) => setLimit(e.target.value)}
              placeholder={mixed ? t('edit.keep') : undefined}
              aria-invalid={limitBad}
            />
          </Field>
        </div>
        <div className="grid min-w-0 gap-3 sm:grid-cols-3">
          <TriField label={t('edit.disableLimit')} value={disableLimit} onChange={setDisableLimit} mixed={mixed} />
          <TriField label={t('edit.surge')} value={surge} onChange={setSurge} mixed={mixed} />
          {!(single && single.type === 'storage') && (
            <TriField label={t('edit.chunkLoading')} value={chunkLoading} onChange={setChunkLoading} mixed={mixed} />
          )}
        </div>
        {storages && mixed && <p className="text-muted-foreground text-xs">{t('edit.storageNoChunks')}</p>}

        {targets.length > 0 && (
          <Field label={t('edit.moveTo')}>
            <select className={cn(SELECT, 'w-full')} value={target} onChange={(e) => setTarget(e.target.value)}>
              <option value="">{t('edit.moveKeep')}</option>
              {targets.map((n) => (
                <option key={n.id} value={n.resource.ref.key}>
                  {plainText(n.name)}
                </option>
              ))}
            </select>
          </Field>
        )}

        {unloaded > 0 && <p className="text-warning text-xs">{t('edit.unloadedSkipped', { count: unloaded })}</p>}
        <Feedback error={error} message={message} />
        <Actions pending={run.isPending} onCancel={onDone} disabled={tooLong || priorityBad || limitBad} />
      </form>
    </li>
  )
}

// --- members ---------------------------------------------------------------------------

/** Members with role changes, removal, handing over and adding players (Flux's own rules). */
export function MemberList({
  networkKey,
  members,
  editable,
}: {
  networkKey: string
  members: FluxMember[]
  editable: boolean
}) {
  const t = useT()
  const run = useRunAction('flux')
  const [confirm, setConfirm] = useState<{ uuid: string; role: 'owner' | 'remove' }>()
  const [adding, setAdding] = useState('')
  const [error, setError] = useState<string>()

  const change = (params: Record<string, unknown>) => {
    setError(undefined)
    run.mutate(
      { type: FLUX_ACTIONS.memberEdit, resource: networkKey, params },
      { onError: (e) => setError(errorText(t, e)), onSuccess: () => setConfirm(undefined) },
    )
  }
  const add = (event: FormEvent) => {
    event.preventDefault()
    if (!adding.trim()) return
    setError(undefined)
    run.mutate(
      { type: FLUX_ACTIONS.memberEdit, resource: networkKey, params: { name: adding.trim(), role: 'user' } },
      { onError: (e) => setError(errorText(t, e)), onSuccess: () => setAdding('') },
    )
  }

  return (
    <Card className="min-w-0 py-1">
      <ul className="divide-border divide-y" data-testid="member-list">
        {members.map((m) => (
          <li key={m.uuid} className="min-w-0 px-4 py-2.5">
            <div className="flex min-w-0 items-center gap-3">
              <span className="bg-primary/15 text-primary flex size-8 shrink-0 items-center justify-center rounded text-sm font-bold">
                {m.name.slice(0, 1).toUpperCase()}
              </span>
              <span className="min-w-0 flex-1 truncate text-sm font-medium">{m.name}</span>
              {editable && (m.access === 'user' || m.access === 'admin') ? (
                <div className="flex shrink-0 items-center gap-1">
                  <select
                    className={SELECT}
                    value={m.access === 'admin' ? 'admin' : 'user'}
                    aria-label={t('edit.role')}
                    disabled={run.isPending}
                    onChange={(e) => change({ player: m.uuid, role: e.target.value })}
                  >
                    <option value="user">{t('energy.access.user')}</option>
                    <option value="admin">{t('energy.access.admin')}</option>
                  </select>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="size-8"
                    title={t('edit.makeOwner')}
                    aria-label={t('edit.makeOwner')}
                    onClick={() => setConfirm({ uuid: m.uuid, role: 'owner' })}
                  >
                    <Crown className="size-3.5" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="size-8"
                    title={t('edit.remove')}
                    aria-label={t('edit.remove')}
                    onClick={() => setConfirm({ uuid: m.uuid, role: 'remove' })}
                  >
                    <Trash2 className="size-3.5" />
                  </Button>
                </div>
              ) : (
                <Badge variant={m.access === 'blocked' ? 'destructive' : 'secondary'}>
                  {t(`energy.access.${m.access}` as MessageKey)}
                </Badge>
              )}
            </div>
            {confirm?.uuid === m.uuid && (
              <div className="mt-2 flex min-w-0 flex-wrap items-center gap-2" data-testid="member-confirm">
                <span className="text-sm">
                  {confirm.role === 'owner' ? t('edit.confirmOwner', { name: m.name }) : t('edit.confirmRemove', { name: m.name })}
                </span>
                <Button
                  size="sm"
                  variant={confirm.role === 'remove' ? 'destructive' : 'default'}
                  disabled={run.isPending}
                  onClick={() => change({ player: m.uuid, role: confirm.role })}
                >
                  {confirm.role === 'owner' ? t('edit.makeOwner') : t('edit.remove')}
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setConfirm(undefined)}>
                  {t('common.cancel')}
                </Button>
              </div>
            )}
          </li>
        ))}
      </ul>
      {editable && (
        <form onSubmit={add} className="border-border flex min-w-0 flex-wrap gap-2 border-t px-4 py-3">
          <Input
            value={adding}
            onChange={(e) => setAdding(e.target.value)}
            placeholder={t('edit.addMemberPlaceholder')}
            maxLength={16}
            className="min-w-0 flex-1 basis-40"
            aria-label={t('edit.addMemberPlaceholder')}
          />
          <Button type="submit" size="sm" disabled={run.isPending || !adding.trim()}>
            <UserPlus /> {t('edit.addMember')}
          </Button>
        </form>
      )}
      {error && (
        <div className="px-4 pb-3">
          <Feedback error={error} />
        </div>
      )}
    </Card>
  )
}

// --- log -------------------------------------------------------------------------------

const FIELD_KEYS: Record<string, MessageKey> = {
  name: 'edit.name',
  color: 'edit.color',
  security: 'edit.security',
  password: 'edit.password',
  priority: 'energy.priority',
  limit: 'edit.limit',
  disableLimit: 'edit.disableLimit',
  surge: 'edit.surge',
  chunkLoading: 'edit.chunkLoading',
  network: 'edit.network',
  access: 'edit.role',
  owner: 'energy.access.owner',
  job: 'cpu.job',
  suspended: 'cpu.suspended',
  mode: 'cpu.mode',
  icon: 'provider.icon',
  blocking: 'provider.blocking',
  lock: 'provider.lockMode',
  visible: 'provider.visible',
}

function value(t: Translate, v: string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '–'
  if (v === 'true') return t('edit.on')
  if (v === 'false') return t('edit.off')
  return plainText(v)
}

/** Who changed what and when (ADR-0016), newest first. */
export function ActionLog({ resource, describe }: { resource: string; describe: (resource: string) => string | undefined }) {
  const t = useT()
  const { locale } = usePreferences()
  const log = useActionLog(resource)
  const entries = log.data ?? []
  if (entries.length === 0) {
    return <p className="text-muted-foreground text-sm">{log.isPending ? t('common.loading') : t('edit.logEmpty')}</p>
  }
  return (
    <Card className="min-w-0 py-1">
      <ul className="divide-border divide-y" data-testid="action-log">
        {entries.map((e) => (
          <LogEntry key={e.id} entry={e} describe={describe} locale={locale} />
        ))}
      </ul>
    </Card>
  )
}

function LogEntry({
  entry: e,
  describe,
  locale,
}: {
  entry: ActionLogEntry
  describe: (resource: string) => string | undefined
  locale: string
}) {
  const t = useT()
  const when = new Date(e.at).toLocaleString(locale, { dateStyle: 'short', timeStyle: 'short' })
  return (
    <li className="min-w-0 px-4 py-2.5">
      <p className="flex min-w-0 flex-wrap items-center gap-x-2 text-sm">
        <History className="text-muted-foreground size-3.5 shrink-0" />
        <span className="font-medium">{e.actorName}</span>
        <span className="text-muted-foreground text-xs">{when}</span>
        {e.operator && <Badge variant="secondary">{t('edit.operator')}</Badge>}
        {e.status !== 'ok' && <Badge variant="destructive">{t(`edit.status.${e.status}` as MessageKey)}</Badge>}
      </p>
      {e.status !== 'ok' && e.message && <p className="text-muted-foreground mt-0.5 text-xs">{e.message}</p>}
      {e.changes.length > 0 && (
        <ul className="text-muted-foreground mt-1 space-y-0.5 text-xs">
          {e.changes.slice(0, 20).map((c, i) => {
            const who = c.resource === e.resource ? undefined : describe(c.resource)
            return (
              <li key={i} className="min-w-0 truncate">
                {who && <span className="text-foreground">{who}: </span>}
                {FIELD_KEYS[c.field] ? t(FIELD_KEYS[c.field]) : c.field} {value(t, c.before)} → {value(t, c.after)}
              </li>
            )
          })}
          {e.changes.length > 20 && <li>{t('edit.moreChanges', { count: e.changes.length - 20 })}</li>}
        </ul>
      )}
    </li>
  )
}
