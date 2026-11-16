import { screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { testUser, renderApp } from '../../test/render'
import { SsoCompletePage } from './SsoCompletePage'

describe('/sso/complete', () => {
  it('adopts the session from the refresh cookie and goes to the role’s home', async () => {
    const completeExternalSignIn = vi.fn().mockResolvedValue(testUser('RECRUITER'))
    renderApp(<SsoCompletePage />, { role: null, route: '/sso/complete', auth: { completeExternalSignIn } })
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/jobs'))
    expect(completeExternalSignIn).toHaveBeenCalledTimes(1)
  })

  it('no usable staff session: says so and links back to sign-in', async () => {
    renderApp(<SsoCompletePage />, { role: null, route: '/sso/complete', auth: { completeExternalSignIn: vi.fn().mockResolvedValue(null) } })
    expect(await screen.findByText(/did not create a session/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to sign in' })).toHaveAttribute('href', '/login')
  })

  it('server unreachable: shows the reason', async () => {
    renderApp(<SsoCompletePage />, { role: null, route: '/sso/complete', auth: { completeExternalSignIn: vi.fn().mockRejectedValue(new ApiError(0, 'The server could not be reached. Try again in a moment.')) } })
    expect(await screen.findByText(/could not be reached/)).toBeInTheDocument()
  })
})
