import { fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Invitation } from '../../api/types'
import { renderApp } from '../../test/render'
import { AdminInvitationsPage } from './AdminInvitationsPage'

vi.mock('../../api/admin', () => ({ adminApi: { invitations: vi.fn(), invite: vi.fn(), revokeInvitation: vi.fn() } }))
import { adminApi } from '../../api/admin'

function invitation(id: number, over: Partial<Invitation> = {}): Invitation {
  return {
    id, email: `person${id}@example.test`, role: 'RECRUITER', expiresAt: '2099-01-01T00:00:00Z', acceptedAt: null, revokedAt: null,
    createdAt: `2026-11-0${id}T10:00:00Z`, invitedByName: 'Ada Admin', ...over,
  }
}

beforeEach(() => {
  vi.mocked(adminApi.invitations).mockResolvedValue([
    invitation(1),
    invitation(2, { status: 'EXPIRED', expiresAt: '2020-01-01T00:00:00Z', role: 'INTERVIEWER' }),
    invitation(3, { status: 'ACCEPTED', acceptedAt: '2026-11-04T10:00:00Z' }),
  ])
  vi.mocked(adminApi.invite).mockImplementation(async (email, role) => invitation(9, { email, role }))
})

describe('/admin/invitations: resend and invite again (completeness sweep)', () => {
  it('resends a pending invitation with the same email and role', async () => {
    renderApp(<AdminInvitationsPage />, { role: 'ADMIN', route: '/admin/invitations' })
    fireEvent.click(await screen.findByRole('button', { name: 'Resend: person1@example.test' }))
    await waitFor(() => expect(adminApi.invite).toHaveBeenCalledWith('person1@example.test', 'RECRUITER'))
    expect(await screen.findByText('Invitation sent again to person1@example.test')).toBeInTheDocument()
  })

  it('invites an expired address again; accepted invitations offer nothing', async () => {
    renderApp(<AdminInvitationsPage />, { role: 'ADMIN', route: '/admin/invitations?show=all' })
    fireEvent.click(await screen.findByRole('button', { name: 'Invite again: person2@example.test' }))
    await waitFor(() => expect(adminApi.invite).toHaveBeenCalledWith('person2@example.test', 'INTERVIEWER'))
    expect(screen.queryByRole('button', { name: /person3@example\.test/ })).not.toBeInTheDocument()
  })
})
