import { Route } from 'react-router-dom'
import { screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { refreshSession, setAccessToken } from '../../api/client'
import { fail, mockApi, ok, refreshRoute, renderPage } from '../../test/utils'
import { PrivacyPage } from '../PrivacyPage'

afterEach(() => {
  // Account deletion blocks refreshes for the page load; a sign-in clears that.
  setAccessToken('reset')
  setAccessToken(null)
})

function renderPrivacy() {
  return renderPage(<PrivacyPage />, { route: '/privacy', path: '/privacy', extraRoutes: <Route path="/login" element={<p>Sign-in page</p>} /> })
}

describe('PrivacyPage: delete account', () => {
  it('asks for the password, confirms with Cancel focused, deletes and signs out for good', async () => {
    const api = mockApi([
      refreshRoute(),
      { path: '/candidates/me/equal-opportunity', reply: fail(404, 'No equal-opportunity answers are stored') },
      { method: 'DELETE', path: '/candidates/me', reply: ok(undefined, 'Your account was deleted') },
    ])
    const { user } = renderPrivacy()
    const submit = await screen.findByRole('button', { name: 'Delete my account…' })
    await user.click(submit)
    expect(await screen.findByText('Enter your password to confirm it is you.')).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()

    await user.type(screen.getByLabelText('Your password'), 'my password 1')
    await user.click(submit)
    const confirm = await screen.findByRole('alertdialog', { name: 'Delete your account permanently?' })
    expect(within(confirm).getByRole('button', { name: 'Cancel' })).toHaveFocus()
    await user.click(within(confirm).getByRole('button', { name: 'Delete my account' }))

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/login'))
    expect(api.find('DELETE', '/candidates/me')[0].body).toEqual({ password: 'my password 1' })
    // The server revoked every session: this page load never tries to refresh again.
    const before = api.find('POST', '/auth/refresh').length
    await expect(refreshSession()).resolves.toBeNull()
    expect(api.find('POST', '/auth/refresh')).toHaveLength(before)
  })

  it('keeps the dialog open with the reason for a wrong password', async () => {
    mockApi([
      refreshRoute(),
      { path: '/candidates/me/equal-opportunity', reply: fail(404, 'No equal-opportunity answers are stored') },
      { method: 'DELETE', path: '/candidates/me', reply: fail(400, 'The password is incorrect') },
    ])
    const { user } = renderPrivacy()
    await user.type(await screen.findByLabelText('Your password'), 'wrong')
    await user.click(screen.getByRole('button', { name: 'Delete my account…' }))
    const confirm = await screen.findByRole('alertdialog')
    await user.click(within(confirm).getByRole('button', { name: 'Delete my account' }))
    expect(await within(confirm).findByText('The password is incorrect')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/privacy')
  })
})

describe('PrivacyPage: survey', () => {
  it('needs consent and at least one answer, then saves with consent: true', async () => {
    const api = mockApi([
      refreshRoute(),
      { path: '/candidates/me/equal-opportunity', reply: fail(404, 'No equal-opportunity answers are stored') },
      { method: 'PUT', path: '/candidates/me/equal-opportunity', reply: ok({ gender: 'WOMAN', ageBand: null, disability: null, socialCategory: null, consentGivenAt: '2026-11-16T10:00:00Z' }) },
    ])
    const { user } = renderPrivacy()
    await user.click(await screen.findByRole('button', { name: 'Save answers' }))
    expect(await screen.findByText('Answer at least one question, or leave the survey empty.')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Gender'), 'WOMAN')
    await user.click(screen.getByRole('button', { name: 'Save answers' }))
    expect(await screen.findByText(/Tick the consent box to save your answers/)).toBeInTheDocument()
    expect(api.find('PUT', '/candidates/me/equal-opportunity')).toHaveLength(0)
    await user.click(screen.getByRole('checkbox', { name: /I agree that RecruitPro stores these answers for fairness monitoring/ }))
    await user.click(screen.getByRole('button', { name: 'Save answers' }))
    await waitFor(() => expect(api.find('PUT', '/candidates/me/equal-opportunity')).toHaveLength(1))
    expect(api.find('PUT', '/candidates/me/equal-opportunity')[0].body).toEqual({ gender: 'WOMAN', ageBand: null, disability: null, socialCategory: null, consent: true })
  })
})
