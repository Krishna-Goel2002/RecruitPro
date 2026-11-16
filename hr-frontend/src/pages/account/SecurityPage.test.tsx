import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { renderApp } from '../../test/render'
import { SecurityPage } from './SecurityPage'

vi.mock('../../api/account', () => ({
  accountApi: { mfaStatus: vi.fn(), mfaEnroll: vi.fn(), mfaConfirm: vi.fn(), mfaDisable: vi.fn(), regenerateRecoveryCodes: vi.fn() },
}))
vi.mock('../../components/QrCode', () => ({ default: () => null }))
import { accountApi } from '../../api/account'

beforeEach(() => {
  vi.mocked(accountApi.mfaStatus).mockResolvedValue({ enabled: true, enabledAt: '2026-11-01T10:00:00Z', pendingEnrollment: false, recoveryCodesRemaining: 2, requiredByOrganization: false, available: true })
  vi.mocked(accountApi.regenerateRecoveryCodes).mockReset()
})

async function openDialog() {
  renderApp(<SecurityPage />)
  fireEvent.click(await screen.findByRole('button', { name: 'Create new recovery codes' }))
  return screen.getByRole('dialog')
}

describe('new recovery codes need a current authenticator code (R1-U1)', () => {
  it('sends the code and shows the new codes', async () => {
    vi.mocked(accountApi.regenerateRecoveryCodes).mockResolvedValue({ recoveryCodes: ['aaaaa-bbb11', 'ccccc-ddd22'] })
    const dialog = await openDialog()
    expect(within(dialog).getByLabelText(/6-digit code/)).toHaveFocus()
    await act(async () => { fireEvent.change(within(dialog).getByLabelText(/6-digit code/), { target: { value: '123456' } }) })
    await waitFor(() => expect(accountApi.regenerateRecoveryCodes).toHaveBeenCalledWith({ code: '123456' }))
    expect(await screen.findByText('aaaaa-bbb11')).toBeInTheDocument()
  })

  it('does not send without six digits', async () => {
    const dialog = await openDialog()
    await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: 'Create new codes' })) })
    expect(await within(dialog).findByText(/Enter the 6-digit code/)).toBeInTheDocument()
    expect(accountApi.regenerateRecoveryCodes).not.toHaveBeenCalled()
  })

  it('a wrong code stays in the dialog; a lockout names the wait', async () => {
    vi.mocked(accountApi.regenerateRecoveryCodes)
      .mockRejectedValueOnce(new ApiError(400, 'The verification code is not valid'))
      .mockRejectedValueOnce(new ApiError(429, 'Too many failed attempts. Please try again later.', [], null, 300))
    const dialog = await openDialog()
    await act(async () => { fireEvent.change(within(dialog).getByLabelText(/6-digit code/), { target: { value: '111111' } }) })
    expect(await within(dialog).findByText(/did not match/)).toBeInTheDocument()
    await act(async () => { fireEvent.change(within(dialog).getByLabelText(/6-digit code/), { target: { value: '222222' } }) })
    expect(await within(dialog).findByText(/paused for about 5 minutes/)).toBeInTheDocument()
  })
})
