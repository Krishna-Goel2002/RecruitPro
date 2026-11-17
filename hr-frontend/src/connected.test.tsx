import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { authApi } from './api/auth'
import App from './App'
import { AuthContext, SIGNED_OUT_NOTICE } from './auth/AuthContext'
import { AppLayout } from './components/Layout'
import brand from './design/brand/active'
import { ToastProvider } from './design/components'
import { routerBasename } from './design/lib/links'
import { LoginPage } from './pages/auth/LoginPage'
import { ForgotPasswordPage } from './pages/auth/PasswordPages'
import { SignupPage } from './pages/auth/SignupPage'
import { NotFoundPage } from './pages/StatusPages'
import { authValue, newQueryClient, renderApp, testUser } from './test/render'

vi.mock('./api/auth', async importOriginal => {
  const actual = await importOriginal<typeof import('./api/auth')>()
  return { ...actual, authApi: { ...actual.authApi, ssoProviders: vi.fn() } }
})

beforeEach(() => {
  vi.mocked(authApi.ssoProviders).mockResolvedValue([])
  // Anything else the shell asks for answers an empty envelope.
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ success: true, message: null, data: null }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
})

// This app's vitest config does not reset stubbed env vars between tests.
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

/** One product on one origin (gateway): every public staff page links home and to the candidate portal. */
describe('connected flows (HR portal)', () => {
  it.each([
    ['/login', <LoginPage key="l" />],
    ['/signup', <SignupPage key="s" />],
    ['/forgot-password', <ForgotPasswordPage key="f" />],
  ])('%s has "Back to home" and the candidate switch (gateway defaults)', (route, page) => {
    renderApp(page, { role: null, route })
    expect(screen.getByRole('link', { name: 'Back to home' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: 'Candidate sign in' })).toHaveAttribute('href', '/candidate/login')
    expect(screen.getByText(/Looking for a job\?/)).toBeInTheDocument()
  })

  it('per-port build: the same links use the configured URLs', () => {
    vi.stubEnv('VITE_SITE_URL', 'http://localhost:5170')
    vi.stubEnv('VITE_CANDIDATE_URL', 'http://localhost:5173')
    renderApp(<LoginPage />, { role: null, route: '/login' })
    expect(screen.getByRole('link', { name: 'Back to home' })).toHaveAttribute('href', 'http://localhost:5170/')
    expect(screen.getByRole('link', { name: 'Candidate sign in' })).toHaveAttribute('href', 'http://localhost:5173/login')
  })

  it('the sign-in page shows the signed-out notice', () => {
    renderApp(<LoginPage />, { role: null, route: '/login', auth: { endedReason: SIGNED_OUT_NOTICE, signedOutByUser: true } })
    expect(screen.getByText('You have signed out.')).toBeInTheDocument()
  })

  it('account menu: a link to the landing site, and Sign out passes the signed-out notice', async () => {
    const user = userEvent.setup()
    const signOut = vi.fn(async () => undefined)
    const value = authValue(testUser('HR_MANAGER'), { signOut })
    render(
      <QueryClientProvider client={newQueryClient()}>
        <MemoryRouter initialEntries={['/jobs']}>
          <ToastProvider>
            <AuthContext.Provider value={value}>
              <Routes>
                <Route element={<AppLayout />}>
                  <Route path="/jobs" element={<p>Jobs</p>} />
                </Route>
              </Routes>
            </AuthContext.Provider>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    )
    await user.click(screen.getByRole('button', { name: /account menu/ }))
    const panel = await screen.findByRole('dialog', { name: 'Account' })
    expect(within(panel).getByRole('link', { name: `${brand.name} home` })).toHaveAttribute('href', '/')
    await user.click(within(panel).getByRole('button', { name: 'Sign out' }))
    expect(signOut).toHaveBeenCalledWith('You have signed out.')
  })

  it('404 offers the home screen and the landing site', () => {
    renderApp(<NotFoundPage />, { route: '/nowhere' })
    expect(screen.getByRole('link', { name: 'Go to your home screen' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: `${brand.name} home` })).toHaveAttribute('href', '/')
  })

  it('base-path routing: under /hr/ (as main.tsx sets it up) routes match and links keep the prefix', async () => {
    window.history.pushState({}, '', '/hr/jobs/1')
    try {
      render(
        <QueryClientProvider client={newQueryClient()}>
          <BrowserRouter basename={routerBasename('/hr/')}>
            <ToastProvider>
              <AuthContext.Provider value={authValue(null)}>
                <App />
              </AuthContext.Provider>
            </ToastProvider>
          </BrowserRouter>
        </QueryClientProvider>,
      )
      // Signed out: the guarded deep link goes to this portal's sign-in, still under /hr.
      expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
      expect(window.location.pathname).toBe('/hr/login')
      expect(screen.getByRole('link', { name: 'Create a workspace' })).toHaveAttribute('href', '/hr/signup')
      expect(screen.getByRole('link', { name: 'Forgot your password?' })).toHaveAttribute('href', '/hr/forgot-password')
    } finally {
      window.history.pushState({}, '', '/')
    }
  })
})
