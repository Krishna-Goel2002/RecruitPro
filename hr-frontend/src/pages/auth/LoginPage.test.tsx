import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { renderApp } from '../../test/render'
import { LoginPage } from './LoginPage'

vi.mock('../../api/auth', () => ({
  authApi: { login: vi.fn(), verifyMfa: vi.fn(), enrollWithToken: vi.fn(), confirmWithToken: vi.fn(), ssoProviders: vi.fn() },
  ssoStartUrl: (id: string) => `http://api.test/auth/sso/${id}/start`,
}))
vi.mock('../../components/QrCode', () => ({ default: ({ label }: { label: string }) => <div role="img" aria-label={label} /> }))
import { authApi } from '../../api/auth'

const user = { id: 1, email: 'hr@example.test', firstName: 'H', lastName: 'R', role: 'HR_MANAGER', emailVerified: true }
const session = { accessToken: 't', user }
const challenge = (enrol: boolean) => ({ mfaRequired: true as const, mfaEnrollmentRequired: enrol, mfaToken: 'mfa-token', expiresInSeconds: 300 })

async function signIn() {
  fireEvent.change(screen.getByLabelText('Work email'), { target: { value: 'hr@example.test' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'correct horse 1' } })
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Sign in' })) })
}

let acceptSession: ReturnType<typeof vi.fn>
function show() {
  acceptSession = vi.fn(async (auth: { user: unknown }) => auth.user)
  return renderApp(<LoginPage />, { role: null, route: '/login', auth: { acceptSession: acceptSession as never } })
}

beforeEach(() => {
  vi.mocked(authApi.ssoProviders).mockResolvedValue([{ id: 'okta', name: 'Okta' }] as never)
})

describe('staff sign-in branching', () => {
  it('password only: adopts the session', async () => {
    vi.mocked(authApi.login).mockResolvedValue(session as never)
    show()
    await signIn()
    expect(acceptSession).toHaveBeenCalledWith(session)
  })

  it('mfaRequired: asks for the code and verifies it with the token (auto-submit on six digits)', async () => {
    vi.mocked(authApi.login).mockResolvedValue(challenge(false))
    vi.mocked(authApi.verifyMfa).mockResolvedValue(session as never)
    show()
    await signIn()
    expect(await screen.findByRole('heading', { name: 'Enter your code' })).toBeInTheDocument()
    await act(async () => { fireEvent.change(screen.getByLabelText('6-digit code'), { target: { value: '123456' } }) })
    await waitFor(() => expect(authApi.verifyMfa).toHaveBeenCalledWith({ mfaToken: 'mfa-token', code: '123456' }))
    expect(acceptSession).toHaveBeenCalledWith(session)
  })

  it('a recovery code can be used instead', async () => {
    vi.mocked(authApi.login).mockResolvedValue(challenge(false))
    vi.mocked(authApi.verifyMfa).mockResolvedValue(session as never)
    show()
    await signIn()
    fireEvent.click(await screen.findByRole('button', { name: 'Use a recovery code instead' }))
    fireEvent.change(screen.getByLabelText('Recovery code'), { target: { value: 'abcde-fgh23' } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Verify and sign in' })) })
    expect(authApi.verifyMfa).toHaveBeenCalledWith({ mfaToken: 'mfa-token', recoveryCode: 'abcde-fgh23' })
  })

  it('a wrong code stays on the step; a lockout goes back to the password with its own message', async () => {
    vi.mocked(authApi.login).mockResolvedValue(challenge(false))
    vi.mocked(authApi.verifyMfa).mockRejectedValueOnce(new ApiError(401, 'The verification code is not valid'))
    vi.mocked(authApi.verifyMfa).mockRejectedValueOnce(new ApiError(401, 'Too many failed attempts. Please try again later.'))
    show()
    await signIn()
    await act(async () => { fireEvent.change(await screen.findByLabelText('6-digit code'), { target: { value: '111111' } }) })
    expect(await screen.findByText(/did not match/)).toBeInTheDocument()
    await act(async () => { fireEvent.change(screen.getByLabelText('6-digit code'), { target: { value: '222222' } }) })
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByText(/Too many wrong codes/)).toBeInTheDocument()
  })

  it('a 429 MFA_LOCKED lockout (integration pass) names the wait from Retry-After', async () => {
    vi.mocked(authApi.login).mockResolvedValue(challenge(false))
    vi.mocked(authApi.verifyMfa).mockRejectedValue(new ApiError(429, 'Too many failed attempts. Try again in 5 minutes.', [], 'MFA_LOCKED', 290))
    show()
    await signIn()
    await act(async () => { fireEvent.change(await screen.findByLabelText('6-digit code'), { target: { value: '333333' } }) })
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByText(/paused for about 5 minutes/)).toBeInTheDocument()
  })

  it('a 429 lockout during enrolment confirm also returns to the password step', async () => {
    vi.mocked(authApi.login).mockResolvedValue(challenge(true))
    vi.mocked(authApi.enrollWithToken).mockResolvedValue({ secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/x?secret=JBSWY3DPEHPK3PXP' })
    vi.mocked(authApi.confirmWithToken).mockRejectedValue(new ApiError(429, 'Too many failed attempts. Try again in 1 minute.', [], 'MFA_LOCKED', 45))
    show()
    await signIn()
    await screen.findByRole('img', { name: /QR code/ })
    await act(async () => { fireEvent.change(screen.getByLabelText(/6-digit code/), { target: { value: '654321' } }) })
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByText(/paused for about a minute/)).toBeInTheDocument()
  })

  it('an expired step goes back to the password', async () => {
    vi.mocked(authApi.login).mockResolvedValue(challenge(false))
    vi.mocked(authApi.verifyMfa).mockRejectedValue(new ApiError(401, 'The sign-in step expired. Sign in again.'))
    show()
    await signIn()
    await act(async () => { fireEvent.change(await screen.findByLabelText('6-digit code'), { target: { value: '111111' } }) })
    expect(await screen.findByText(/step expired/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('mfaEnrollmentRequired: starts enrolment with the token, confirms, then shows recovery codes before signing in', async () => {
    vi.mocked(authApi.login).mockResolvedValue(challenge(true))
    vi.mocked(authApi.enrollWithToken).mockResolvedValue({ secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/x?secret=JBSWY3DPEHPK3PXP' })
    vi.mocked(authApi.confirmWithToken).mockResolvedValue({ recoveryCodes: ['abcde-fgh23', 'klmno-pqr45'], session } as never)
    show()
    await signIn()
    expect(await screen.findByRole('heading', { name: 'Set up two-step verification' })).toBeInTheDocument()
    await waitFor(() => expect(authApi.enrollWithToken).toHaveBeenCalledWith('mfa-token'))
    expect(await screen.findByRole('img', { name: /QR code/ })).toBeInTheDocument()
    await act(async () => { fireEvent.change(screen.getByLabelText(/6-digit code/), { target: { value: '654321' } }) })
    await waitFor(() => expect(authApi.confirmWithToken).toHaveBeenCalledWith('mfa-token', '654321'))
    expect(await screen.findByRole('heading', { name: 'Save your recovery codes' })).toBeInTheDocument()
    expect(acceptSession).not.toHaveBeenCalled()
  })

  it('enrolment on a server without an encryption key explains it instead of offering a useless retry', async () => {
    vi.mocked(authApi.login).mockResolvedValue(challenge(true))
    vi.mocked(authApi.enrollWithToken).mockRejectedValue(new ApiError(503, 'Two-step verification is not available on this server yet (no encryption key is configured). Ask your administrator.', [], 'MFA_NOT_CONFIGURED'))
    show()
    await signIn()
    expect(await screen.findByText(/not available on this server/)).toBeInTheDocument()
    expect(screen.queryByText(/MFA_NOT_CONFIGURED/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
  })

  it('SSO providers sit under the primary button and start a top-level navigation', async () => {
    show()
    const link = await screen.findByRole('link', { name: /Continue with Okta/ })
    expect(link).toHaveAttribute('href', 'http://api.test/auth/sso/okta/start')
  })

  it('reads ?ssoError once and removes it from the URL', async () => {
    renderApp(<LoginPage />, { role: null, route: '/login?ssoError=no_account' })
    expect(await screen.findByText(/no .* staff account/i)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/login'))
  })
})
