import type { ReactElement, ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import type { AuthUser, Role } from '../api/types'
import { AuthContext, type AuthContextValue } from '../auth/AuthContext'
import { can as roleCan } from '../auth/permissions'
import { ToastProvider } from '../design/components'

export function testUser(role: Role = 'HR_MANAGER', extra: Partial<AuthUser> = {}): AuthUser {
  return { id: 7, email: 'reviewer@example.test', firstName: 'Ria', lastName: 'Reviewer', role, organizationId: 1, organizationName: 'Example Org', emailVerified: true, ...extra }
}

export function authValue(user: AuthUser | null, extra: Partial<AuthContextValue> = {}): AuthContextValue {
  return {
    status: user ? 'authenticated' : 'anonymous',
    user,
    endedReason: null,
    signedOutByUser: false,
    can: capability => roleCan(user?.role, capability),
    acceptSession: async auth => auth.user,
    signOut: async () => undefined,
    completeExternalSignIn: async () => user,
    updateUser: () => undefined,
    ...extra,
  }
}

export function newQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity }, mutations: { retry: false } } })
}

/** Shows the current location (path + search) for assertions on navigation and URL state. */
export function LocationProbe() {
  const location = useLocation()
  return <output data-testid="location">{`${location.pathname}${location.search}`}</output>
}

export function renderApp(ui: ReactElement, {
  route = '/',
  path = '*',
  role = 'HR_MANAGER' as Role | null,
  auth,
  queryClient = newQueryClient(),
  extraRoutes,
}: { route?: string; path?: string; role?: Role | null; auth?: Partial<AuthContextValue>; queryClient?: QueryClient; extraRoutes?: ReactNode } = {}) {
  const value = authValue(role ? testUser(role) : null, auth)
  const result = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <ToastProvider>
          <AuthContext.Provider value={value}>
            <Routes>
              <Route path={path} element={<>{ui}<LocationProbe /></>} />
              {extraRoutes}
            </Routes>
          </AuthContext.Provider>
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...result, queryClient, auth: value }
}
