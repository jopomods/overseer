import { createRootRoute, createRoute, createRouter, Outlet } from '@tanstack/react-router'
import { AppShell } from '@/components/app-shell'
import { AccountPage } from '@/pages/account'
import { LoginPage } from '@/pages/login'
import { OverviewPage } from '@/pages/overview'
import { ServerPage } from '@/pages/server'

/**
 * Routes. Every route listed here must also be in web/e2e/layout.spec.ts (AGENTS.md rule 6).
 */
const rootRoute = createRootRoute({ component: Outlet })

const loginRoute = createRoute({ getParentRoute: () => rootRoute, path: '/login', component: LoginPage })

/** Pathless layout route: requires login, renders the navigation shell. */
const appRoute = createRoute({ getParentRoute: () => rootRoute, id: 'app', component: AppShell })

const overviewRoute = createRoute({ getParentRoute: () => appRoute, path: '/', component: OverviewPage })
const serverRoute = createRoute({ getParentRoute: () => appRoute, path: '/server', component: ServerPage })
const accountRoute = createRoute({ getParentRoute: () => appRoute, path: '/account', component: AccountPage })

const routeTree = rootRoute.addChildren([loginRoute, appRoute.addChildren([overviewRoute, serverRoute, accountRoute])])

export const router = createRouter({ routeTree, defaultPreload: 'intent' })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
