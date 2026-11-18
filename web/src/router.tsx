import { createRootRoute, createRoute, createRouter, lazyRouteComponent, Outlet } from '@tanstack/react-router'
import { AppShell } from '@/components/app-shell'
import { LoginPage } from '@/pages/login'
import { OverviewPage } from '@/pages/overview'

/**
 * Pages other than login and overview load on demand (smaller first load on phones).
 * Every route listed here must also be in web/e2e/layout.spec.ts (AGENTS.md rule 6).
 */
const rootRoute = createRootRoute({ component: Outlet })

const loginRoute = createRoute({ getParentRoute: () => rootRoute, path: '/login', component: LoginPage })

/** Pathless layout route: requires login, renders the navigation shell. */
const appRoute = createRoute({ getParentRoute: () => rootRoute, id: 'app', component: AppShell })

const overviewRoute = createRoute({ getParentRoute: () => appRoute, path: '/', component: OverviewPage })
const serverRoute = createRoute({ getParentRoute: () => appRoute, path: '/server', component: lazyRouteComponent(() => import('@/pages/server'), 'ServerPage') })
const accountRoute = createRoute({ getParentRoute: () => appRoute, path: '/account', component: lazyRouteComponent(() => import('@/pages/account'), 'AccountPage') })
const energyRoute = createRoute({ getParentRoute: () => appRoute, path: '/energy', component: lazyRouteComponent(() => import('@/pages/energy'), 'EnergyPage') })
const networkRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/energy/$networkId',
  component: lazyRouteComponent(() => import('@/pages/network'), 'NetworkPage'),
})

const ae2Route = createRoute({ getParentRoute: () => appRoute, path: '/ae2', component: lazyRouteComponent(() => import('@/pages/ae2'), 'Ae2Page') })
const gridRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/ae2/$gridId',
  component: lazyRouteComponent(() => import('@/pages/grid'), 'GridPage'),
})
const storageRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/admin/storage',
  component: lazyRouteComponent(() => import('@/pages/storage'), 'StoragePage'),
})

const routeTree = rootRoute.addChildren([loginRoute, appRoute.addChildren([overviewRoute, serverRoute, energyRoute, networkRoute, ae2Route, gridRoute, storageRoute, accountRoute])])

export const router = createRouter({ routeTree, defaultPreload: 'intent' })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
