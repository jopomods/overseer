import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Navigate } from '@tanstack/react-router'
import { Database, Download, Eraser, HardDrive } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { api } from '@/api/client'
import { unwrap } from '@/api/hooks'
import type { components } from '@/api/schema.gen'
import { useSession } from '@/components/session'
import { PageHeader, Section, Stat } from '@/components/stat'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { formatBytes, formatNumber } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { usePreferences } from '@/lib/preferences'

type Storage = components['schemas']['Storage']
type Settings = components['schemas']['StorageSettings']

const HOUR = 3_600_000
const DAY = 24 * HOUR
const GIB = 1024 ** 3

/** Admin page: database size per provider, retention, purge, VACUUM, backup (ADR-0006). */
export function StoragePage() {
  const t = useT()
  const { me } = useSession()
  const client = useQueryClient()
  const storage = useQuery({
    queryKey: ['admin-storage'],
    queryFn: () => unwrap(api.GET('/api/v1/admin/storage')),
    enabled: me.role !== 'player',
    refetchInterval: 30_000,
  })
  const refresh = () => client.invalidateQueries({ queryKey: ['admin-storage'] })

  if (me.role === 'player' || me.deviceToken) return <Navigate to="/" />

  return (
    <>
      <PageHeader title={t('storage.title')} />
      {storage.data && <Overview storage={storage.data} />}
      {storage.data && (
        <Settings key={JSON.stringify(storage.data.settings)} settings={storage.data.settings} onSaved={refresh} />
      )}
      {storage.data && <Providers storage={storage.data} onPurged={refresh} />}
      <Maintenance onDone={refresh} />
    </>
  )
}

function Overview({ storage }: { storage: Storage }) {
  const t = useT()
  const { locale } = usePreferences()
  const total = storage.fileBytes + storage.walBytes
  const tooBig = storage.settings.warnBytes > 0 && total > storage.settings.warnBytes
  return (
    <div className="grid min-w-0 grid-cols-2 gap-3 lg:grid-cols-4" data-testid="storage-overview">
      <Stat
        label={t('storage.size')}
        value={formatBytes(total, locale)}
        tone={tooBig ? 'warn' : undefined}
        hint={tooBig ? t('storage.tooBig', { limit: formatBytes(storage.settings.warnBytes, locale) }) : `WAL ${formatBytes(storage.walBytes, locale)}`}
      />
      <Stat
        label={t('storage.series')}
        value={formatNumber(storage.providers.reduce((s, p) => s + p.series, 0), locale)}
      />
    </div>
  )
}

/** Retention in friendly units: raw in hours, rollups in days, warning in GiB. 0 = forever/never. */
function Settings({ settings, onSaved }: { settings: Settings; onSaved: () => void }) {
  const t = useT()
  // Remounted (key) when the server values change, so plain initial state suffices.
  const [raw, setRaw] = useState(() => String(settings.rawMillis / HOUR))
  const [minute, setMinute] = useState(() => String(settings.minuteMillis / DAY))
  const [hour, setHour] = useState(() => String(settings.hourMillis / DAY))
  const [warn, setWarn] = useState(() => String(Math.round((settings.warnBytes / GIB) * 100) / 100))

  const save = useMutation({
    mutationFn: (body: Settings) => unwrap(api.PUT('/api/v1/admin/storage/settings', { body })),
    onSuccess: onSaved,
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    save.mutate({
      rawMillis: Math.round(Number(raw) * HOUR),
      minuteMillis: Math.round(Number(minute) * DAY),
      hourMillis: Math.round(Number(hour) * DAY),
      warnBytes: Math.round(Number(warn) * GIB),
    })
  }
  const field = (label: string, unit: string, value: string, set: (v: string) => void) => (
    <label className="flex min-w-0 flex-col gap-1.5">
      <span className="text-muted-foreground truncate text-xs">{label}</span>
      <span className="flex min-w-0 items-center gap-2">
        <Input type="number" min={0} step="any" value={value} onChange={(e) => set(e.target.value)} className="min-w-0 flex-1" />
        <span className="text-muted-foreground w-10 shrink-0 text-xs">{unit}</span>
      </span>
    </label>
  )

  return (
    <Section title={t('storage.retention')}>
      <Card className="min-w-0 py-4">
        <CardContent className="min-w-0 px-4">
          <form onSubmit={submit} className="space-y-4" data-testid="storage-settings">
            <div className="grid min-w-0 gap-4 sm:grid-cols-2 lg:grid-cols-4">
              {field(t('storage.raw'), t('storage.unit.hours'), raw, setRaw)}
              {field(t('storage.minute'), t('storage.unit.days'), minute, setMinute)}
              {field(t('storage.hour'), t('storage.unit.days'), hour, setHour)}
              {field(t('storage.warn'), 'GiB', warn, setWarn)}
            </div>
            <div className="flex min-w-0 flex-wrap items-center justify-between gap-3">
              <p className="text-muted-foreground min-w-0 text-xs">{t('storage.zeroHint')}</p>
              <Button type="submit" disabled={save.isPending}>
                {t('common.save')}
              </Button>
            </div>
            {save.isError && <p className="text-destructive text-sm">{t('storage.invalid')}</p>}
          </form>
        </CardContent>
      </Card>
    </Section>
  )
}

function Providers({ storage, onPurged }: { storage: Storage; onPurged: () => void }) {
  const t = useT()
  const { locale } = usePreferences()
  const [confirm, setConfirm] = useState<string | null>(null)
  const purge = useMutation({
    mutationFn: (provider: string) => unwrap(api.POST('/api/v1/admin/storage/purge', { body: { provider } })),
    onSuccess: () => {
      setConfirm(null)
      onPurged()
    },
  })
  return (
    <Section title={t('storage.providers')}>
      <div className="grid min-w-0 gap-3 sm:grid-cols-2" data-testid="storage-providers">
        {storage.providers.map((p) => (
          <Card key={p.provider} className="min-w-0 gap-2 py-4">
            <CardContent className="min-w-0 space-y-3 px-4">
              <div className="flex min-w-0 items-center justify-between gap-2">
                <span className="flex min-w-0 items-center gap-2 font-medium">
                  <Database className="text-muted-foreground size-4 shrink-0" />
                  <span className="truncate">{p.provider}</span>
                </span>
                <Badge variant="secondary">{t('storage.seriesCount', { count: p.series })}</Badge>
              </div>
              <dl className="tabular grid grid-cols-3 gap-2 text-xs">
                {(
                  [
                    [t('storage.raw'), p.raw],
                    [t('storage.minute'), p.minute],
                    [t('storage.hour'), p.hour],
                  ] as const
                ).map(([label, value]) => (
                  <div key={label} className="min-w-0">
                    <dt className="text-muted-foreground truncate">{label}</dt>
                    <dd className="truncate text-sm font-semibold">{formatNumber(value, locale)}</dd>
                  </div>
                ))}
              </dl>
              {confirm === p.provider ? (
                <div className="flex min-w-0 flex-wrap items-center gap-2">
                  <span className="text-destructive min-w-0 text-xs">{t('storage.purgeConfirm')}</span>
                  <Button size="sm" variant="destructive" onClick={() => purge.mutate(p.provider)} disabled={purge.isPending}>
                    {t('storage.purge')}
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => setConfirm(null)}>
                    {t('common.cancel')}
                  </Button>
                </div>
              ) : (
                <Button size="sm" variant="outline" onClick={() => setConfirm(p.provider)}>
                  <Eraser /> {t('storage.purge')}
                </Button>
              )}
            </CardContent>
          </Card>
        ))}
      </div>
    </Section>
  )
}

function Maintenance({ onDone }: { onDone: () => void }) {
  const t = useT()
  const vacuum = useMutation({
    mutationFn: () => unwrap(api.POST('/api/v1/admin/storage/vacuum')),
    onSuccess: onDone,
  })
  return (
    <Section title={t('storage.maintenance')}>
      <div className="flex min-w-0 flex-wrap gap-2">
        <Button variant="outline" onClick={() => vacuum.mutate()} disabled={vacuum.isPending}>
          <HardDrive /> {vacuum.isPending ? t('common.loading') : t('storage.vacuum')}
        </Button>
        <Button variant="outline" asChild>
          <a href="/api/v1/admin/storage/backup" download>
            <Download /> {t('storage.backup')}
          </a>
        </Button>
      </div>
      <p className="text-muted-foreground mt-2 text-xs">{t('storage.maintenanceHint')}</p>
    </Section>
  )
}
