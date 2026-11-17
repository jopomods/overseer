import { Link, Navigate, Outlet, useRouterState } from '@tanstack/react-router'
import { LayoutDashboard, LogOut, Server, UserRound, Zap, type LucideIcon } from 'lucide-react'
import { ApiError, useLogout, useMe, useProviders, type Me } from '@/api/hooks'
import { useLiveUpdates } from '@/api/live'
import { SessionContext } from '@/components/session'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Skeleton } from '@/components/ui/skeleton'
import { useT, type MessageKey } from '@/lib/i18n'
import { cn } from '@/lib/utils'

interface NavItem {
  to: '/' | '/server' | '/energy' | '/account'
  /** Shown only when this provider is active on the server. */
  provider?: string
  label: MessageKey
  icon: LucideIcon
}

const NAV: NavItem[] = [
  { to: '/', label: 'nav.overview', icon: LayoutDashboard },
  { to: '/energy', label: 'nav.energy', icon: Zap, provider: 'flux' },
  { to: '/server', label: 'nav.server', icon: Server },
  { to: '/account', label: 'nav.account', icon: UserRound },
]

/**
 * Authenticated layout: sidebar on desktop, top bar + bottom navigation on phones.
 * Not logged in → login page.
 */
export function AppShell() {
  const me = useMe()
  const live = useLiveUpdates(me.isSuccess)

  if (me.error instanceof ApiError && me.error.status === 401) return <Navigate to="/login" />
  if (!me.data) return <ShellSkeleton error={me.isError} retry={() => me.refetch()} />

  return (
    <SessionContext.Provider value={{ me: me.data, live }}>
      <div className="flex min-h-dvh">
        <Sidebar me={me.data} />
        <div className="flex min-w-0 flex-1 flex-col">
          <TopBar me={me.data} />
          <main className="mx-auto w-full max-w-6xl min-w-0 flex-1 px-4 pt-4 pb-24 md:px-8 md:pt-8 md:pb-10">
            <Outlet />
          </main>
        </div>
        <BottomNav />
      </div>
    </SessionContext.Provider>
  )
}

/** Navigation entries, without modules the server does not have. */
function useNav(): NavItem[] {
  const providers = useProviders().data
  return NAV.filter((item) => !item.provider || providers?.some((p) => p.id === item.provider))
}

function useActive() {
  const path = useRouterState({ select: (s) => s.location.pathname })
  return (to: string) => (to === '/' ? path === '/' : path === to || path.startsWith(`${to}/`))
}

function Logo() {
  return (
    <Link to="/" className="flex min-w-0 items-center gap-2.5">
      <img src="/overseer.svg" alt="" className="size-7 shrink-0 [image-rendering:pixelated]" />
      <span className="truncate text-base font-semibold tracking-tight">Overseer</span>
    </Link>
  )
}

function Sidebar({ me }: { me: Me }) {
  const t = useT()
  const nav = useNav()
  const isActive = useActive()
  return (
    <aside className="bg-sidebar border-sidebar-border sticky top-0 hidden h-dvh w-60 shrink-0 flex-col border-r p-3 md:flex">
      <div className="px-2 py-3">
        <Logo />
      </div>
      <nav className="mt-4 flex flex-col gap-1">
        {nav.map((item) => (
          <Link
            key={item.to}
            to={item.to}
            className={cn(
              'text-sidebar-foreground/80 hover:bg-sidebar-accent hover:text-sidebar-foreground flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
              isActive(item.to) && 'bg-sidebar-accent text-sidebar-foreground',
            )}
          >
            <item.icon className={cn('size-4', isActive(item.to) && 'text-primary')} />
            {t(item.label)}
          </Link>
        ))}
      </nav>
      <div className="mt-auto">
        <UserMenu me={me} align="start" />
      </div>
    </aside>
  )
}

function TopBar({ me }: { me: Me }) {
  return (
    <header className="bg-background/85 border-border sticky top-0 z-20 flex h-14 items-center justify-between gap-3 border-b px-4 backdrop-blur md:hidden">
      <Logo />
      <UserMenu me={me} align="end" compact />
    </header>
  )
}

function BottomNav() {
  const t = useT()
  const nav = useNav()
  const isActive = useActive()
  return (
    <nav
      className="bg-background/95 border-border fixed inset-x-0 bottom-0 z-20 grid border-t backdrop-blur md:hidden"
      style={{ gridTemplateColumns: `repeat(${nav.length}, minmax(0, 1fr))`, paddingBottom: 'env(safe-area-inset-bottom)' }}
    >
      {nav.map((item) => (
        <Link
          key={item.to}
          to={item.to}
          className={cn(
            'text-muted-foreground flex min-w-0 flex-col items-center gap-1 py-2 text-[11px] font-medium',
            isActive(item.to) && 'text-primary',
          )}
        >
          <item.icon className="size-5" />
          <span className="max-w-full truncate px-1">{t(item.label)}</span>
        </Link>
      ))}
    </nav>
  )
}

function UserMenu({ me, align, compact }: { me: Me; align: 'start' | 'end'; compact?: boolean }) {
  const t = useT()
  const logout = useLogout()
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" className={cn('h-auto min-w-0 gap-2.5 px-2 py-1.5', !compact && 'w-full justify-start')}>
          {/* No external skin service: it would leak UUIDs to a third party and break the CSP. */}
          <span className="bg-primary/15 text-primary flex size-7 shrink-0 items-center justify-center rounded text-xs font-bold">
            {me.name.slice(0, 1).toUpperCase()}
          </span>
          {!compact && (
            <span className="min-w-0 text-left">
              <span className="block truncate text-sm font-medium">{me.name}</span>
              <span className="text-muted-foreground block truncate text-xs">{t(`account.role.${me.role}`)}</span>
            </span>
          )}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align={align} className="w-56">
        <DropdownMenuLabel className="truncate">{me.name}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild>
          <Link to="/account">
            <UserRound /> {t('nav.account')}
          </Link>
        </DropdownMenuItem>
        {!me.deviceToken && (
          <DropdownMenuItem onSelect={() => logout.mutate()}>
            <LogOut /> {t('nav.logout')}
          </DropdownMenuItem>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function ShellSkeleton({ error, retry }: { error: boolean; retry: () => void }) {
  const t = useT()
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-4 p-6">
      {error ? (
        <>
          <p className="text-muted-foreground text-sm">{t('login.error')}</p>
          <Button variant="outline" onClick={retry}>
            {t('common.retry')}
          </Button>
        </>
      ) : (
        <div className="w-full max-w-sm space-y-3">
          <Skeleton className="h-8 w-40" />
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
      )}
    </div>
  )
}
