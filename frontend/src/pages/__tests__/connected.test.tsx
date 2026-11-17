import { render, screen, within } from '@testing-library/react'
import { Route } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import App from '../../App'
import { Layout } from '../../components/Layout'
import brand from '../../design/brand/active'
import { fail, mockApi, ok, refreshRoute, renderPage } from '../../test/utils'
import { AuthLayout } from '../auth/AuthLayout'
import { LoginPage } from '../auth/LoginPage'
import { NotFoundPage } from '../NotFoundPage'

/** One product on one origin (gateway): sign out lands on this portal's sign-in, which links home and to the HR portal. */
describe('connected flows (candidate portal)', () => {
  it('sign out: back to the sign-in page with a signed-out notice, a way home and the HR switch', async () => {
    const api = mockApi([refreshRoute(), { method: 'POST', path: '/auth/logout', reply: ok() }])
    const { user } = renderPage(<Layout />, {
      route: '/home',
      path: '/home',
      extraRoutes: (
        <Route element={<AuthLayout />}>
          <Route path="/login" element={<LoginPage />} />
        </Route>
      ),
    })

    await user.click(await screen.findByRole('button', { name: /account menu/ }))
    const panel = await screen.findByRole('dialog', { name: 'Account' })
    expect(within(panel).getByRole('link', { name: `${brand.name} home` })).toHaveAttribute('href', '/')

    await user.click(within(panel).getByRole('button', { name: 'Sign out' }))
    expect(await screen.findByText('You have signed out.')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/login$/)
    expect(api.find('POST', '/auth/logout')).toHaveLength(1)
    expect(screen.getByRole('link', { name: 'Back to home' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: 'Sign in to the HR workspace' })).toHaveAttribute('href', '/hr/login')
  })

  it('404 offers the overview and the landing site', async () => {
    mockApi([refreshRoute()])
    renderPage(<NotFoundPage />, { route: '/nowhere' })
    expect(await screen.findByRole('link', { name: 'Go to your overview' })).toHaveAttribute('href', '/home')
    expect(screen.getByRole('link', { name: `${brand.name} home` })).toHaveAttribute('href', '/')
  })

  it('base-path routing: under /candidate/ the router strips the prefix and in-app links keep it', async () => {
    vi.stubEnv('BASE_URL', '/candidate/')
    mockApi([{ method: 'POST', path: '/auth/refresh', reply: fail(401, 'No session') }])
    window.history.pushState({}, '', '/candidate/applications')
    try {
      render(<App />)
      // Signed out: the guarded route sends the visitor to this portal's sign-in, still under /candidate.
      expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
      expect(window.location.pathname).toBe('/candidate/login')
      expect(screen.getByRole('link', { name: 'Create an account' })).toHaveAttribute('href', '/candidate/register')
      expect(screen.getByRole('link', { name: 'Forgot your password?' })).toHaveAttribute('href', '/candidate/forgot-password')
      expect(screen.getByRole('link', { name: 'Back to home' })).toHaveAttribute('href', '/')
    } finally {
      window.history.pushState({}, '', '/')
    }
  })
})
