/** Test helpers: a fetch mock that speaks the backend envelope, and a render with the app's providers. */
import type { ReactElement } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { vi } from 'vitest'
import { API_BASE_URL } from '../api/client'
import type { AuthResponse, AuthUser } from '../api/types'
import { AuthProvider } from '../auth/AuthProvider'
import { RequireAuth } from '../auth/guards'
import { ToastProvider } from '../design/components'

export const CANDIDATE: AuthUser = {
  id: 7,
  email: 'meera@example.com',
  firstName: 'Meera',
  lastName: 'Iyer',
  role: 'CANDIDATE',
  emailVerified: true,
}

export function session(user: AuthUser = CANDIDATE, token = 'access-1'): AuthResponse {
  return { accessToken: token, tokenType: 'Bearer', expiresInSeconds: 900, user }
}

export interface Reply {
  status?: number
  /** Envelope data (wrapped as { success, message, data }). */
  data?: unknown
  message?: string
  errors?: { field: string; message: string }[]
  /** A raw body instead of an envelope. */
  raw?: string
  headers?: Record<string, string>
}

export interface Call {
  method: string
  path: string
  url: URL
  headers: Headers
  /** Parsed JSON body, the FormData, or undefined. */
  body: unknown
  credentials?: RequestCredentials
}

export interface MockRoute {
  method?: string
  /** Path after /api/v1, exact string or a RegExp tested against the path (no query). */
  path: string | RegExp
  reply: Reply | ((call: Call) => Reply | Promise<Reply>)
  /** Answer only this many times (then the next matching route is used). */
  times?: number
}

export const ok = (data?: unknown, message = 'Operation completed successfully'): Reply => ({ status: 200, data, message })
export const fail = (status: number, message: string, errors?: Reply['errors']): Reply => ({ status, message, errors })

export function toResponse(reply: Reply): Response {
  const status = reply.status ?? 200
  const body =
    reply.raw !== undefined
      ? reply.raw
      : JSON.stringify({
          success: status < 400,
          message: reply.message,
          ...(reply.data !== undefined ? { data: reply.data } : {}),
          ...(reply.errors ? { errors: reply.errors } : {}),
          timestamp: '2026-11-16T10:00:00Z',
        })
  return new Response(status === 204 ? null : body, { status, headers: { 'Content-Type': 'application/json', ...reply.headers } })
}

/** Installs a fetch mock for /api/v1 calls. Unmatched calls answer 404 and are listed in `unmatched`. */
export function mockApi(routes: MockRoute[]) {
  const calls: Call[] = []
  const unmatched: string[] = []
  const used = new Map<MockRoute, number>()
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    // The API base is relative ("/api/v1", same origin) by default; resolve against the page like the browser does.
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, window.location.href)
    const method = (init.method ?? 'GET').toUpperCase()
    const path = url.pathname.replace(new URL(API_BASE_URL, window.location.href).pathname, '')
    let body: unknown = init.body
    if (typeof init.body === 'string') {
      try {
        body = JSON.parse(init.body)
      } catch {
        body = init.body
      }
    }
    const call: Call = { method, path, url, headers: new Headers(init.headers), body, credentials: init.credentials }
    calls.push(call)
    const route = routes.find(r => {
      if ((r.method ?? 'GET').toUpperCase() !== method) return false
      if (r.times !== undefined && (used.get(r) ?? 0) >= r.times) return false
      return typeof r.path === 'string' ? r.path === path : r.path.test(path)
    })
    if (!route) {
      unmatched.push(`${method} ${path}`)
      return toResponse(fail(404, `No mock for ${method} ${path}`))
    }
    used.set(route, (used.get(route) ?? 0) + 1)
    const reply = typeof route.reply === 'function' ? await route.reply(call) : route.reply
    return toResponse(reply)
  })
  vi.stubGlobal('fetch', fetchMock)
  const find = (method: string, path: string | RegExp) =>
    calls.filter(c => c.method === method && (typeof path === 'string' ? c.path === path : path.test(c.path)))
  return { calls, unmatched, fetchMock, find }
}

/** The signed-in candidate's session restore (POST /auth/refresh). */
export const refreshRoute = (user: AuthUser = CANDIDATE): MockRoute => ({ method: 'POST', path: '/auth/refresh', reply: ok(session(user)) })

export function newQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false }, mutations: { retry: false } } })
}

/** Shows the current location so tests can assert navigation. */
function LocationProbe() {
  const location = useLocation()
  return <output data-testid="location">{`${location.pathname}${location.search}`}</output>
}

/**
 * Renders `ui` at `route` inside the app's providers. With `auth` (default) the element sits behind RequireAuth, so
 * mock POST /auth/refresh (see `refreshRoute`). `/login` renders a marker unless `extraRoutes` replaces it.
 */
export function renderPage(
  ui: ReactElement,
  { route = '/', path = '*', auth = true, extraRoutes }: { route?: string; path?: string; auth?: boolean; extraRoutes?: ReactElement } = {},
) {
  const queryClient = newQueryClient()
  const user = userEvent.setup()
  const result = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <ToastProvider>
          <AuthProvider>
            <LocationProbe />
            <Routes>
              {extraRoutes}
              {!extraRoutes && <Route path="/login" element={<p>Sign-in page</p>} />}
              {auth ? (
                <Route element={<RequireAuth />}>
                  <Route path={path} element={ui} />
                </Route>
              ) : (
                <Route path={path} element={ui} />
              )}
            </Routes>
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...result, queryClient, user }
}

export function file(name: string, size = 1024, type = 'application/pdf'): File {
  const f = new File(['x'], name, { type })
  Object.defineProperty(f, 'size', { value: size })
  return f
}
