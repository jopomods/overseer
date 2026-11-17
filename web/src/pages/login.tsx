import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { Check, Copy, RefreshCw } from 'lucide-react'
import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import { ApiError, keys, unwrap } from '@/api/hooks'
import { useNow } from '@/lib/use-now'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { formatDuration } from '@/lib/format'
import { useT } from '@/lib/i18n'
import { usePreferences } from '@/lib/preferences'

/**
 * Link-code login (ADR-0007): request a code, show `/ov link <code>`, poll until the
 * player confirms in game. The poll response sets the session cookie.
 */
export function LoginPage() {
  const t = useT()
  const { locale } = usePreferences()
  const navigate = useNavigate()
  const client = useQueryClient()
  const now = useNow(1000)
  const [copied, setCopied] = useState(false)

  const create = useMutation({ mutationFn: () => unwrap(api.POST('/api/v1/auth/link-codes')) })
  const code = create.data
  const remaining = code ? Date.parse(code.expiresAt) - now : 0
  const expired = !!code && remaining <= 0

  const status = useQuery({
    queryKey: ['link-status', code?.code],
    queryFn: () => unwrap(api.GET('/api/v1/auth/link-codes/current')),
    enabled: !!code && !expired,
    refetchInterval: 2000,
  })

  // Request a code once on arrival.
  const { mutate, isIdle } = create
  useEffect(() => {
    if (isIdle) mutate()
  }, [isIdle, mutate])

  useEffect(() => {
    if (status.data?.status === 'confirmed') {
      client.resetQueries({ queryKey: keys.me }).then(() => navigate({ to: '/' }))
    }
  }, [status.data, client, navigate])

  const command = code ? `/ov link ${code.code}` : ''
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(command)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // Clipboard blocked; the command is selectable.
    }
  }

  const failed = create.isError || status.data?.status === 'expired' || expired
  const rateLimited = create.error instanceof ApiError && create.error.status === 429

  return (
    <main className="flex min-h-dvh items-center justify-center p-4">
      <Card className="w-full max-w-md min-w-0">
        <CardContent className="flex min-w-0 flex-col items-center gap-5 px-5 text-center sm:px-8">
          <img src="/overseer.svg" alt="" className="size-14 [image-rendering:pixelated]" />
          <div className="min-w-0 space-y-1.5">
            <h1 className="text-2xl font-semibold tracking-tight">{t('login.title')}</h1>
            <p className="text-muted-foreground text-sm text-balance">{t('login.intro')}</p>
          </div>

          {code && !failed && (
            <>
              <button
                type="button"
                onClick={copy}
                className="bg-muted hover:bg-accent group flex w-full min-w-0 items-center justify-center gap-3 rounded-lg px-4 py-4 transition-colors"
                data-testid="link-command"
              >
                <code className="tabular min-w-0 truncate font-mono text-lg font-semibold tracking-wider sm:text-xl">
                  <span className="text-muted-foreground">/ov link </span>
                  <span className="text-primary">{code.code}</span>
                </code>
                {copied ? <Check className="text-success size-4 shrink-0" /> : <Copy className="text-muted-foreground size-4 shrink-0" />}
              </button>
              <div className="text-muted-foreground flex flex-col items-center gap-1 text-sm">
                <span className="flex items-center gap-2">
                  <span className="bg-primary size-2 animate-pulse rounded-full" />
                  {t('login.waiting')}
                </span>
                <span className="tabular text-xs">
                  {t('login.expiresIn', { time: formatDuration(remaining, locale) })}
                </span>
              </div>
            </>
          )}

          {failed && (
            <div className="flex flex-col items-center gap-3">
              <p className="text-muted-foreground text-sm">
                {rateLimited ? t('login.rateLimited') : create.isError ? t('login.error') : t('login.expired')}
              </p>
              <Button onClick={() => create.mutate()} disabled={create.isPending}>
                <RefreshCw /> {t('login.newCode')}
              </Button>
            </div>
          )}

          {!code && !failed && <p className="text-muted-foreground text-sm">{t('common.loading')}</p>}
        </CardContent>
      </Card>
    </main>
  )
}
