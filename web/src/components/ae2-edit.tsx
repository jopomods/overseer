import { Pause, Pencil, Play, Square } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useRunAction } from '@/api/hooks'
import { Actions, Counter, Feedback, Field } from '@/components/edit-form'
import { McText } from '@/components/mc-text'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { AE2_ACTIONS, CPU_MODES, MAX_AE2_NAME, type Ae2Cpu, type Ae2Drive } from '@/lib/ae2'
import { errorText, SELECT } from '@/lib/edit'
import { useT } from '@/lib/i18n'
import { plainText } from '@/lib/mc-text'

/**
 * Operating a crafting CPU from the dashboard (ADR-0017): suspend or resume and cancel its job,
 * name it, choose who may use it. Only the actions the server allows are shown.
 */
export function CpuControls({ grid, cpu, allowed, title }: { grid: string; cpu: Ae2Cpu; allowed: Set<string>; title: string }) {
  const t = useT()
  const run = useRunAction('ae2')
  const [confirm, setConfirm] = useState(false)
  const [editing, setEditing] = useState(false)
  const canCancel = allowed.has(AE2_ACTIONS.cpuCancel)
  const canSuspend = allowed.has(AE2_ACTIONS.cpuSuspend)
  const canEdit = allowed.has(AE2_ACTIONS.cpuEdit)
  if (!canCancel && !canSuspend && !canEdit) return null
  const job = cpu.job

  const act = (type: string, params: Record<string, unknown>, onSuccess?: () => void) =>
    run.mutate({ type, resource: grid, params: { cpu: cpu.id, ...params } }, { onSuccess })

  return (
    <div className="min-w-0 space-y-2" data-testid="cpu-controls">
      {confirm && job ? (
        <div className="flex min-w-0 flex-wrap items-center gap-2" data-testid="cpu-confirm-cancel">
          <span className="min-w-0 text-sm">
            {t('cpu.confirmCancel', { name: plainText(job.name) })}
          </span>
          <Button
            size="sm"
            variant="destructive"
            disabled={run.isPending}
            onClick={() => act(AE2_ACTIONS.cpuCancel, {}, () => setConfirm(false))}
          >
            <Square /> {t('cpu.cancelJob')}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setConfirm(false)}>
            {t('common.cancel')}
          </Button>
        </div>
      ) : (
        <div className="flex min-w-0 flex-wrap gap-2">
          {job && canSuspend && (
            <Button
              size="sm"
              variant="outline"
              disabled={run.isPending}
              onClick={() => act(AE2_ACTIONS.cpuSuspend, { suspended: !cpu.suspended })}
            >
              {cpu.suspended ? <Play /> : <Pause />} {cpu.suspended ? t('cpu.resume') : t('cpu.suspend')}
            </Button>
          )}
          {job && canCancel && (
            <Button size="sm" variant="outline" onClick={() => setConfirm(true)}>
              <Square /> {t('cpu.cancelJob')}
            </Button>
          )}
          {canEdit && !editing && (
            <Button size="sm" variant="ghost" onClick={() => setEditing(true)}>
              <Pencil /> {t('cpu.edit')}
            </Button>
          )}
        </div>
      )}
      {editing && <CpuEditForm grid={grid} cpu={cpu} title={title} onDone={() => setEditing(false)} />}
      {!editing && <Feedback error={run.error ? errorText(t, run.error) : undefined} />}
    </div>
  )
}

function CpuEditForm({ grid, cpu, title, onDone }: { grid: string; cpu: Ae2Cpu; title: string; onDone: () => void }) {
  const t = useT()
  const run = useRunAction('ae2')
  const [name, setName] = useState(cpu.name ?? '')
  const [mode, setMode] = useState(cpu.mode)
  const tooLong = name.trim().length > MAX_AE2_NAME

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const params: Record<string, unknown> = {}
    if (name.trim() !== (cpu.name ?? '')) params.name = name.trim()
    if (mode !== cpu.mode) params.mode = mode
    if (Object.keys(params).length === 0) return onDone()
    run.mutate({ type: AE2_ACTIONS.cpuEdit, resource: grid, params: { cpu: cpu.id, ...params } }, { onSuccess: onDone })
  }

  return (
    <form onSubmit={submit} className="min-w-0 space-y-3" data-testid="cpu-edit">
      <Field label={t('edit.name')} hint={<Counter value={name.trim()} max={MAX_AE2_NAME} />}>
        <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={title} aria-invalid={tooLong} autoFocus />
      </Field>
      <p className="text-muted-foreground text-xs">{t('cpu.nameHint')}</p>
      {name.includes('§') && (
        <p className="text-sm">
          <span className="text-muted-foreground text-xs">{t('edit.preview')}: </span>
          <McText text={name} />
        </p>
      )}
      <Field label={t('cpu.mode')}>
        <select className={`${SELECT} w-full`} value={mode} onChange={(e) => setMode(e.target.value)}>
          {CPU_MODES.map((m) => (
            <option key={m} value={m}>
              {t(`cpu.mode.${m}`)}
            </option>
          ))}
        </select>
      </Field>
      <Feedback error={run.error ? errorText(t, run.error) : undefined} />
      <Actions pending={run.isPending} onCancel={onDone} disabled={tooLong} />
    </form>
  )
}

/** The storage priority of a drive or ME chest; editable for those who may act on the grid. */
export function DrivePriority({ grid, drive, editable }: { grid: string; drive: Ae2Drive; editable: boolean }) {
  const t = useT()
  const run = useRunAction('ae2')
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState(String(drive.priority ?? 0))
  if (drive.priority === null) return null
  const parsed = Number(value)
  const valid = value.trim() !== '' && Number.isInteger(parsed) && Math.abs(parsed) <= 2_147_483_647

  if (!editing) {
    return (
      <div className="text-muted-foreground flex min-w-0 items-center justify-between gap-2 text-xs">
        <span className="tabular">
          {t('drive.priority')}: {drive.priority}
        </span>
        {editable && (
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={t('drive.editPriority')}
            onClick={() => {
              setValue(String(drive.priority ?? 0))
              setEditing(true)
            }}
          >
            <Pencil />
          </Button>
        )}
      </div>
    )
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (parsed === drive.priority) return setEditing(false)
    run.mutate(
      { type: AE2_ACTIONS.driveEdit, resource: grid, params: { drive: drive.id, priority: parsed } },
      { onSuccess: () => setEditing(false) },
    )
  }
  return (
    <form onSubmit={submit} className="min-w-0 space-y-2" data-testid="drive-priority">
      <Field label={t('drive.priority')}>
        <Input type="number" step={1} value={value} onChange={(e) => setValue(e.target.value)} aria-invalid={!valid} autoFocus />
      </Field>
      <Feedback error={run.error ? errorText(t, run.error) : undefined} />
      <Actions pending={run.isPending} onCancel={() => setEditing(false)} disabled={!valid} />
    </form>
  )
}
