import { Route } from 'react-router-dom'
import { screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { setAccessToken } from '../../api/client'
import { isPublicPath } from '../../auth/AuthProvider'
import { redirectTarget } from '../../auth/guards'
import { CANDIDATE, fail, mockApi, ok, refreshRoute, renderPage, session } from '../../test/utils'
import { LoginPage } from '../auth/LoginPage'

beforeEach(() => {
  setAccessToken('reset-block')
  setAccessToken(null)
})

const anonymous = { method: 'POST', path: '/auth/refresh', reply: fail(401, 'Invalid or expired refresh token') }

function renderLogin() {
  return renderPage(<LoginPage />, {
    route: '/login',
    path: '/login',
    auth: false,
    extraRoutes: (
      <>
        <Route path="/home" element={<p>Overview</p>} />
        <Route path="/applications/:id" element={<p>Application page</p>} />
      </>
    ),
  })
}

describe('route helpers', () => {
  it('treats only the referee link and the style guide as public', () => {
    expect(isPublicPath('/reference/abc123')).toBe(true)
    expect(isPublicPath('/__design')).toBe(true)
    expect(isPublicPath('/reference')).toBe(false)
    expect(isPublicPath('/reference/abc/extra')).toBe(false)
    expect(isPublicPath('/home')).toBe(false)
  })

  it('redirects only to safe in-app paths after sign-in', () => {
    expect(redirectTarget({ from: '/applications/3?tab=x' })).toBe('/applications/3?tab=x')
    expect(redirectTarget({ from: '//evil.example' })).toBe('/home')
    expect(redirectTarget({ from: 'https://evil.example' })).toBe('/home')
    expect(redirectTarget({ from: '/login' })).toBe('/home')
    expect(redirectTarget(null)).toBe('/home')
  })
})

describe('LoginPage', () => {
  it('validates before calling the server and focuses the first invalid field', async () => {
    const api = mockApi([anonymous])
    const { user } = renderLogin()
    await user.click(await screen.findByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText('Enter the email address you registered with.')).toBeInTheDocument()
    expect(screen.getByText('Enter your password.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('Email')).toHaveFocus())
    expect(api.find('POST', '/auth/login')).toHaveLength(0)
  })

  it('signs in and continues to the overview', async () => {
    const api = mockApi([anonymous, { method: 'POST', path: '/auth/login', reply: ok(session()) }])
    const { user } = renderLogin()
    await user.type(await screen.findByLabelText('Email'), ' meera@example.com ')
    await user.type(screen.getByLabelText('Password'), 'correct horse 1')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/home'))
    expect(api.find('POST', '/auth/login')[0].body).toEqual({ email: 'meera@example.com', password: 'correct horse 1' })
  })

  it('shows the server message for a staff account (403) and stays on the page', async () => {
    mockApi([
      anonymous,
      { method: 'POST', path: '/auth/login', reply: fail(403, 'This is the candidate portal. Staff accounts sign in to the RecruitPro HR portal.') },
    ])
    const { user } = renderLogin()
    await user.type(await screen.findByLabelText('Email'), 'hr@example.com')
    await user.type(screen.getByLabelText('Password'), 'whatever123')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText(/Staff accounts sign in to the RecruitPro HR portal/)).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/login')
  })

  it('offers Try again when the session could not be restored (server unreachable)', async () => {
    const api = mockApi([{ method: 'POST', path: '/auth/refresh', reply: fail(503, 'Unavailable'), times: 1 }, refreshRoute(CANDIDATE)])
    const { user } = renderLogin()
    expect(await screen.findByText('We could not reach the server to restore your session.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(api.find('POST', '/auth/refresh')).toHaveLength(2))
  })
})
