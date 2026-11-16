import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { renderApp } from '../../test/render'
import { AcceptInvitePage } from './AcceptInvitePage'

vi.mock('../../api/auth', () => ({
  authApi: { previewInvitation: vi.fn(), acceptInvite: vi.fn(), verifyMfa: vi.fn(), enrollWithToken: vi.fn(), confirmWithToken: vi.fn() },
}))
vi.mock('../../components/QrCode', () => ({ default: ({ label }: { label: string }) => <div role="img" aria-label={label} /> }))
import { authApi } from '../../api/auth'

const user = { id: 9, email: 'new.recruiter@northwind.example', firstName: 'New', lastName: 'Recruiter', role: 'RECRUITER', emailVerified: true }
const session = { accessToken: 't', user }
const challenge = (enrol: boolean) => ({ mfaRequired: true as const, mfaEnrollmentRequired: enrol, mfaToken: 'invite-mfa', expiresInSeconds: 300 })

let acceptSession: ReturnType<typeof vi.fn>
function show() {
  acceptSession = vi.fn(async (auth: { user: unknown }) => auth.user)
  return renderApp(<AcceptInvitePage />, { role: null, route: '/accept-invite?token=inv-123', auth: { acceptSession: acceptSession as never } })
}

async function join() {
  fireEvent.change(await screen.findByLabelText('First name'), { target: { value: 'New' } })
  fireEvent.change(screen.getByLabelText('Last name'), { target: { value: 'Recruiter' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'Correct horse 12' } })
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Join workspace' })) })
}

beforeEach(() => {
  vi.mocked(authApi.previewInvitation).mockResolvedValue({ organizationName: 'Northwind Labs', email: user.email, role: 'RECRUITER', expiresAt: '2030-01-01T00:00:00Z' } as never)
})

describe('accepting an invitation', () => {
  it('without required MFA: adopts the session and opens the role home', async () => {
    vi.mocked(authApi.acceptInvite).mockResolvedValue(session as never)
    show()
    await join()
    expect(authApi.acceptInvite).toHaveBeenCalledWith({ token: 'inv-123', firstName: 'New', lastName: 'Recruiter', password: 'Correct horse 12' })
    expect(acceptSession).toHaveBeenCalledWith(session)
    await waitFor(() => expect(screen.getByTestId('location').textContent).not.toContain('/accept-invite'))
  })

  it('in an organisation that requires MFA: enrol with the token, confirm, save the codes, then sign in', async () => {
    vi.mocked(authApi.acceptInvite).mockResolvedValue(challenge(true))
    vi.mocked(authApi.enrollWithToken).mockResolvedValue({ secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/x?secret=JBSWY3DPEHPK3PXP' })
    vi.mocked(authApi.confirmWithToken).mockResolvedValue({ recoveryCodes: ['abcde-fgh23', 'klmno-pqr45'], session } as never)
    show()
    await join()
    expect(await screen.findByRole('heading', { name: 'Set up two-step verification' })).toBeInTheDocument()
    await waitFor(() => expect(authApi.enrollWithToken).toHaveBeenCalledWith('invite-mfa'))
    await screen.findByRole('img', { name: /QR code/ })
    await act(async () => { fireEvent.change(screen.getByLabelText(/6-digit code/), { target: { value: '654321' } }) })
    await waitFor(() => expect(authApi.confirmWithToken).toHaveBeenCalledWith('invite-mfa', '654321'))
    expect(await screen.findByRole('heading', { name: 'Save your recovery codes' })).toBeInTheDocument()
    expect(acceptSession).not.toHaveBeenCalled()
    fireEvent.click(screen.getByLabelText(/I have saved these codes/))
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Continue to workspace' })) })
    await waitFor(() => expect(acceptSession).toHaveBeenCalledWith(session))
  })

  it('an existing MFA set-up asks for the code (verify with the token)', async () => {
    vi.mocked(authApi.acceptInvite).mockResolvedValue(challenge(false))
    vi.mocked(authApi.verifyMfa).mockResolvedValue(session as never)
    show()
    await join()
    await act(async () => { fireEvent.change(await screen.findByLabelText('6-digit code'), { target: { value: '123456' } }) })
    await waitFor(() => expect(authApi.verifyMfa).toHaveBeenCalledWith({ mfaToken: 'invite-mfa', code: '123456' }))
    expect(acceptSession).toHaveBeenCalledWith(session)
  })

  it('a lockout leaves the step with the wait and points to sign-in', async () => {
    vi.mocked(authApi.acceptInvite).mockResolvedValue(challenge(true))
    vi.mocked(authApi.enrollWithToken).mockResolvedValue({ secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/x?secret=JBSWY3DPEHPK3PXP' })
    vi.mocked(authApi.confirmWithToken).mockRejectedValue(new ApiError(429, 'Too many failed attempts. Try again in 10 minutes.', [], 'MFA_LOCKED', 600))
    show()
    await join()
    await screen.findByRole('img', { name: /QR code/ })
    await act(async () => { fireEvent.change(screen.getByLabelText(/6-digit code/), { target: { value: '654321' } }) })
    expect(await screen.findByRole('heading', { name: 'Your account is ready' })).toBeInTheDocument()
    expect(screen.getByText(/paused for about 10 minutes\./)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to sign in' })).toHaveAttribute('href', '/login')
    expect(acceptSession).not.toHaveBeenCalled()
  })
})
