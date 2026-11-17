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

const routeTree = rootRoute.addChildren([loginRoute, appRoute.addChildren([overviewRoute, serverRoute, energyRoute, networkRoute, accountRoute])])

export const router = createRouter({ routeTree, defaultPreload: 'intent' })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
