import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { PublicReference } from '../../api/types'
import { fail, mockApi, ok, renderPage } from '../../test/utils'
import { RefereePage } from '../RefereePage'

const TOKEN = 'tok_abc-123'
const PATH = `/public/references/${TOKEN}`

const reference: PublicReference = {
  candidateFirstName: 'Meera',
  relationship: 'MANAGER',
  employerName: 'Acme Payments',
  claims: [
    { id: 'fact:12', claimType: 'SKILL', value: 'PostgreSQL query tuning' },
    { id: 'claim:7', claimType: 'EXPERIENCE', value: 'Led the refunds team' },
  ],
  expiresAt: '2026-11-30T10:00:00Z',
}

function renderReferee() {
  return renderPage(<RefereePage />, { route: `/reference/${TOKEN}`, path: '/reference/:token', auth: false })
}

describe('RefereePage', () => {
  it('never touches the candidate session: no /auth/refresh on the public page', async () => {
    const api = mockApi([{ path: PATH, reply: ok(reference) }])
    renderReferee()
    expect(await screen.findByRole('heading', { level: 1, name: /Can you confirm Meera.s work\?/ })).toBeInTheDocument()
    expect(api.find('POST', '/auth/refresh')).toHaveLength(0)
    expect(api.calls.every(c => c.headers.get('Authorization') === null)).toBe(true)
  })

  it('shows "not valid" for an unknown token (404)', async () => {
    mockApi([{ path: PATH, reply: fail(404, 'Reference request not found') }])
    renderReferee()
    expect(await screen.findByRole('heading', { name: 'This link is not valid' })).toBeInTheDocument()
  })

  it('shows "closed" for an expired or answered request (410)', async () => {
    mockApi([{ path: PATH, reply: fail(410, 'This reference request has expired') }])
    renderReferee()
    expect(await screen.findByRole('heading', { name: 'This request is closed' })).toBeInTheDocument()
  })

  it('offers Try again for other failures (429)', async () => {
    mockApi([{ path: PATH, reply: fail(429, 'Too many requests. Please try again later.') }])
    renderReferee()
    expect(await screen.findByText('Too many requests. Please try again later.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('takes the referee to the first unanswered claim instead of sending', async () => {
    const api = mockApi([{ path: PATH, reply: ok(reference) }])
    const { user } = renderReferee()
    await screen.findByRole('heading', { level: 1 })
    await user.click(screen.getByRole('button', { name: 'Send answers' }))
    expect(await screen.findAllByText('Choose an answer.')).toHaveLength(2)
    expect(screen.getByText('Please confirm who you are.')).toBeInTheDocument()
    const first = screen.getByRole('radiogroup', { name: 'Your answer for PostgreSQL query tuning' })
    await waitFor(() => expect(within(first).getAllByRole('radio')[0]).toHaveFocus())
    expect(api.find('POST', PATH)).toHaveLength(0)
  })

  it('sends every answer with comments and the identity confirmation, then thanks the referee', async () => {
    const api = mockApi([
      { path: PATH, reply: ok(reference) },
      { method: 'POST', path: PATH, reply: ok(undefined, 'Thank you for your answers.') },
    ])
    const { user } = renderReferee()
    await screen.findByRole('heading', { level: 1 })
    await user.click(within(screen.getByRole('radiogroup', { name: 'Your answer for PostgreSQL query tuning' })).getByLabelText('Confirm'))
    await user.click(within(screen.getByRole('radiogroup', { name: 'Your answer for Led the refunds team' })).getByLabelText("Can't confirm"))
    expect(screen.getByText('2', { selector: 'strong' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add a comment about Led the refunds team' }))
    await user.type(screen.getByLabelText(/Comment/), '  I joined later.  ')
    await user.click(screen.getByLabelText(/I confirm that I am the person this request was sent to/))
    await user.click(screen.getByRole('button', { name: 'Send answers' }))
    expect(await screen.findByRole('heading', { name: 'Thank you' })).toBeInTheDocument()
    expect(api.find('POST', PATH)[0].body).toEqual({
      answers: [
        { claimId: 'fact:12', answer: 'CONFIRM', comment: null },
        { claimId: 'claim:7', answer: 'CANNOT_CONFIRM', comment: 'I joined later.' },
      ],
      refereeComment: null,
      confirmIdentity: true,
    })
  })

  it('declines after a confirmation', async () => {
    const api = mockApi([
      { path: PATH, reply: ok(reference) },
      { method: 'POST', path: PATH, reply: ok(undefined, 'Thank you. The request was declined.') },
    ])
    const { user } = renderReferee()
    await screen.findByRole('heading', { level: 1 })
    await user.click(screen.getByRole('button', { name: 'I would rather not answer' }))
    const dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: 'Decline' }))
    expect(await screen.findByRole('heading', { name: 'Request declined' })).toBeInTheDocument()
    expect(api.find('POST', PATH)[0].body).toEqual({ decline: true })
  })

  it('explains a request that closed while the page was open (410 on submit)', async () => {
    mockApi([
      { path: PATH, reply: ok(reference) },
      { method: 'POST', path: PATH, reply: fail(410, 'This reference request is closed') },
    ])
    const { user } = renderReferee()
    await screen.findByRole('heading', { level: 1 })
    for (const claim of reference.claims) {
      await user.click(within(screen.getByRole('radiogroup', { name: `Your answer for ${claim.value}` })).getByLabelText('Partly'))
    }
    await user.click(screen.getByLabelText(/I confirm that I am the person/))
    await user.click(screen.getByRole('button', { name: 'Send answers' }))
    expect(await screen.findByText('This request has expired or was already answered.')).toBeInTheDocument()
  })
})
