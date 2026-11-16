import { fireEvent, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { renderApp, testUser } from '../../test/render'
import { NamePage } from './NamePage'

vi.mock('../../api/account', () => ({ accountApi: { updateName: vi.fn() } }))
import { accountApi } from '../../api/account'

describe('/account/name (completeness sweep)', () => {
  it('saves a corrected name, updates the signed-in user and confirms', async () => {
    const updateUser = vi.fn()
    vi.mocked(accountApi.updateName).mockImplementation(async (firstName, lastName) => testUser('RECRUITER', { firstName, lastName }))
    renderApp(<NamePage />, { role: 'RECRUITER', auth: { updateUser } })
    const save = screen.getByRole('button', { name: 'No changes' })
    expect(save).toBeDisabled()
    fireEvent.change(screen.getByRole('textbox', { name: 'Last name' }), { target: { value: '  Reviewa ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save name' }))
    await waitFor(() => expect(accountApi.updateName).toHaveBeenCalledWith('Ria', 'Reviewa'))
    expect(updateUser).toHaveBeenCalledWith({ firstName: 'Ria', lastName: 'Reviewa' })
    expect(await screen.findByText('Name updated')).toBeInTheDocument()
  })

  it('needs both names', async () => {
    renderApp(<NamePage />, { role: 'RECRUITER' })
    fireEvent.change(screen.getByRole('textbox', { name: 'First name' }), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save name' }))
    expect(await screen.findByText('Enter your first name.')).toBeInTheDocument()
    expect(accountApi.updateName).not.toHaveBeenCalled()
  })
})
