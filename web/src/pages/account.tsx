import { KeyRound, Laptop, LogOut, Plus } from 'lucide-react'
import { useState, type FormEvent, type ReactNode } from 'react'
import { useCreateDeviceToken, useCredentials, useLogoutEverywhere, useRevokeCredential } from '@/api/hooks'
import { useSession } from '@/components/session'
import { useNow } from '@/lib/use-now'
import { PageHeader, Section } from '@/components/stat'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { formatDuration } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { setPreferences, usePreferences, type EnergyUnit, type Locale, type TimeAxis } from '@/lib/preferences'
import { cn } from '@/lib/utils'

export function AccountPage() {
  const t = useT()
  const { me } = useSession()
  return (
    <>
      <PageHeader title={t('account.title')} />
      <Card className="min-w-0 py-4">
        <CardContent className="flex min-w-0 items-center gap-4 px-4">
          <span className="bg-primary/15 text-primary flex size-12 shrink-0 items-center justify-center rounded-lg text-lg font-bold">
            {me.name.slice(0, 1).toUpperCase()}
          </span>
          <div className="min-w-0">
            <p className="truncate text-lg font-semibold">{me.name}</p>
            <p className="text-muted-foreground truncate text-sm">
              {t('account.role')}: {t(`account.role.${me.role}`)}
            </p>
          </div>
        </CardContent>
      </Card>

      <Preferences />
      {me.deviceToken ? (
        <p className="text-muted-foreground mt-6 text-sm">{t('account.deviceTokenNotice')}</p>
      ) : (
        <Credentials />
      )}
    </>
  )
}

function Choice<T extends string>({
  value,
  options,
  onChange,
}: {
  value: T
  options: { value: T; label: string }[]
  onChange: (value: T) => void
}) {
  return (
    <div className="bg-muted inline-flex shrink-0 rounded-lg p-1">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          onClick={() => onChange(o.value)}
          className={cn(
            'rounded-md px-3 py-1 text-sm font-medium transition-colors',
            value === o.value ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex min-w-0 flex-wrap items-center justify-between gap-3 px-4 py-3">
      <span className="min-w-0 truncate text-sm">{label}</span>
      {children}
    </div>
  )
}

function Preferences() {
  const t = useT()
  const prefs = usePreferences()
  return (
    <Section title={t('account.preferences')}>
      <Card className="divide-border min-w-0 divide-y py-1">
        <Row label={t('account.language')}>
          <Choice<Locale>
            value={prefs.locale}
            options={[
              { value: 'de', label: 'Deutsch' },
              { value: 'en', label: 'English' },
            ]}
            onChange={(locale) => setPreferences({ locale })}
          />
        </Row>
        <Row label={t('account.timeAxis')}>
          <Choice<TimeAxis>
            value={prefs.axis}
            options={[
              { value: 'uptime', label: t('chart.uptime') },
              { value: 'calendar', label: t('chart.calendar') },
            ]}
            onChange={(axis) => setPreferences({ axis })}
          />
        </Row>
        <Row label={t('account.energyUnit')}>
          <Choice<EnergyUnit>
            value={prefs.energyUnit}
            options={[
              { value: 'fe', label: 'FE / RF' },
              { value: 'eu', label: 'EU' },
            ]}
            onChange={(energyUnit) => setPreferences({ energyUnit })}
          />
        </Row>
      </Card>
    </Section>
  )
}

function Credentials() {
  const t = useT()
  const { locale } = usePreferences()
  const now = useNow(30_000)
  const credentials = useCredentials()
  const revoke = useRevokeCredential()
  const everywhere = useLogoutEverywhere()

  return (
    <Section
      title={t('account.sessions')}
      aside={
        <Button variant="outline" size="sm" onClick={() => everywhere.mutate()} disabled={everywhere.isPending}>
          <LogOut /> <span className="hidden sm:inline">{t('account.logoutEverywhere')}</span>
        </Button>
      }
    >
      <Card className="min-w-0 py-1">
        <ul className="divide-border divide-y" data-testid="credentials">
          {credentials.data?.map((c) => (
            <li key={c.id} className="flex min-w-0 items-center gap-3 px-4 py-3">
              {c.kind === 'session' ? (
                <Laptop className="text-muted-foreground size-4 shrink-0" />
              ) : (
                <KeyRound className="text-muted-foreground size-4 shrink-0" />
              )}
              <div className="min-w-0 flex-1">
                <p className="flex min-w-0 items-center gap-2 text-sm font-medium">
                  <span className="truncate">
                    {c.kind === 'device_token' ? c.name : t('account.session')}
                  </span>
                  {c.current && <Badge variant="secondary">{t('account.thisDevice')}</Badge>}
                </p>
                <p className="text-muted-foreground truncate text-xs" title={c.name ?? undefined}>
                  {t('account.lastUsed', { age: formatDuration(now - Date.parse(c.lastUsedAt), locale) })}
                  {c.kind === 'session' && c.name ? ` · ${c.name}` : ''}
                </p>
              </div>
              {!c.current && (
                <Button variant="ghost" size="sm" onClick={() => revoke.mutate(c.id)} disabled={revoke.isPending}>
                  {t('common.revoke')}
                </Button>
              )}
            </li>
          ))}
        </ul>
      </Card>
      <NewDeviceToken />
    </Section>
  )
}

function NewDeviceToken() {
  const t = useT()
  const create = useCreateDeviceToken()
  const [name, setName] = useState('')

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (name.trim()) create.mutate(name.trim(), { onSuccess: () => setName('') })
  }

  return (
    <div className="mt-3 min-w-0 space-y-3">
      <form onSubmit={submit} className="flex min-w-0 gap-2">
        <Input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder={t('account.tokenName')}
          maxLength={64}
          className="min-w-0 flex-1"
          aria-label={t('account.newToken')}
        />
        <Button type="submit" disabled={!name.trim() || create.isPending}>
          <Plus /> <span className="hidden sm:inline">{t('common.create')}</span>
        </Button>
      </form>
      {create.data && (
        <Card className="border-primary/40 min-w-0 py-3">
          <CardContent className="min-w-0 space-y-2 px-4">
            <p className="text-sm">{t('account.tokenCreated')}</p>
            <code className="bg-muted block rounded px-2 py-1.5 font-mono text-xs break-all select-all">
              {create.data.token}
            </code>
            <Button variant="ghost" size="sm" onClick={() => create.reset()}>
              {t('common.close')}
            </Button>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
