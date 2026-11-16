import { useState } from 'react'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { VerificationSource } from '../../api/types'
import { fail, file, mockApi, ok, refreshRoute, renderPage } from '../../test/utils'
import { AddSourceDrawer, type AddSourceState } from '../verification/AddSourceDrawer'
import { useSources } from '../verification/useSources'

const SOURCES = '/candidates/me/verification/sources'

function website(extra: Partial<VerificationSource> = {}): VerificationSource {
  return {
    id: 42, kind: 'WEBSITE', identifier: 'https://meera.dev', displayLabel: 'meera.dev', ownershipStatus: 'UNPROVEN', ownershipMethod: null,
    challengeCode: 'rp-Zx81kQ2mNw0aB7cD9eF1gH3j', ownershipInstructions: [], consentedAt: '2026-11-16T10:00:00Z', lastRun: null,
    ownershipCheckedAt: null, ownershipDetail: null, createdAt: '2026-11-16T10:00:00Z', ...extra,
  }
}

function Harness({ initial, onState }: { initial: AddSourceState; onState?: (s: AddSourceState) => void }) {
  const [state, setState] = useState(initial)
  const sources = useSources()
  return (
    <AddSourceDrawer
      open
      state={state}
      onChange={next =>
        setState(current => {
          const merged = { ...current, ...next }
          onState?.(merged)
          return merged
        })
      }
      onClose={() => undefined}
      sources={sources}
      ledger={[]}
      onReview={() => undefined}
    />
  )
}

const WEBSITE_CONSENT =
  'I agree that RecruitPro may read the public pages of this website to check the claims on my resume. I can revoke this at any time.'

describe('AddSourceDrawer: consent gating', () => {
  it('sends nothing until the consent box is ticked, then connects with consent: true', async () => {
    // The server's list: empty until the POST connects the source.
    let stored: VerificationSource[] = []
    const api = mockApi([
      refreshRoute(),
      { path: SOURCES, reply: () => ok(stored) },
      {
        method: 'POST',
        path: SOURCES,
        reply: () => {
          stored = [website()]
          return ok(website(), 'Source connected')
        },
      },
    ])
    const onState = vi.fn()
    const { user } = renderPage(<Harness initial={{ step: 'connect', kind: 'WEBSITE', sourceId: null }} onState={onState} />)
    const drawer = await screen.findByRole('dialog', { name: 'Connect Website' })
    await user.type(within(drawer).getByLabelText('Website address'), 'https://meera.dev')
    await user.click(within(drawer).getByRole('button', { name: 'Connect' }))
    expect(await within(drawer).findByText(/Please tick the consent box first/)).toBeInTheDocument()
    await waitFor(() => expect(within(drawer).getByRole('checkbox', { name: WEBSITE_CONSENT })).toHaveFocus())
    expect(api.find('POST', SOURCES)).toHaveLength(0)

    await user.click(within(drawer).getByRole('checkbox', { name: WEBSITE_CONSENT }))
    await user.click(within(drawer).getByRole('button', { name: 'Connect' }))
    await waitFor(() => expect(api.find('POST', SOURCES)).toHaveLength(1))
    expect(api.find('POST', SOURCES)[0].body).toEqual({ kind: 'WEBSITE', consent: true, identifier: 'https://meera.dev' })
    // Unproven website: the drawer moves on to "Prove" with the DNS record to publish.
    await waitFor(() => expect(onState).toHaveBeenLastCalledWith({ step: 'prove', kind: 'WEBSITE', sourceId: 42 }))
    expect(await within(drawer).findByText('recruitpro-verification=rp-Zx81kQ2mNw0aB7cD9eF1gH3j')).toBeInTheDocument()
  })

  it('validates the address before consent matters', async () => {
    const api = mockApi([refreshRoute(), { path: SOURCES, reply: ok([]) }])
    const { user } = renderPage(<Harness initial={{ step: 'connect', kind: 'WEBSITE', sourceId: null }} />)
    const drawer = await screen.findByRole('dialog')
    await user.click(within(drawer).getByRole('checkbox', { name: WEBSITE_CONSENT }))
    await user.type(within(drawer).getByLabelText('Website address'), 'javascript:alert(1)')
    await user.click(within(drawer).getByRole('button', { name: 'Connect' }))
    expect(await within(drawer).findByText('Enter the full address, starting with https://.')).toBeInTheDocument()
    expect(api.find('POST', SOURCES)).toHaveLength(0)
  })

  it('shows "already connected" for a 409', async () => {
    mockApi([refreshRoute(), { path: SOURCES, reply: ok([]) }, { method: 'POST', path: SOURCES, reply: fail(409, 'This source is already connected') }])
    const { user } = renderPage(<Harness initial={{ step: 'connect', kind: 'GITLAB', sourceId: null }} />)
    const drawer = await screen.findByRole('dialog')
    await user.type(within(drawer).getByLabelText('GitLab username'), 'meera')
    await user.click(within(drawer).getByRole('checkbox', { name: /may read my public GitLab profile/ }))
    await user.click(within(drawer).getByRole('button', { name: 'Connect' }))
    expect(await within(drawer).findByText(/This source is already connected. Revoke the existing one first/)).toBeInTheDocument()
  })

  it('uploads a document as multipart with consent=true', async () => {
    const api = mockApi([
      refreshRoute(),
      { path: SOURCES, reply: ok([]) },
      { method: 'POST', path: `${SOURCES}/documents`, reply: ok(website({ id: 9, kind: 'DOCUMENT', identifier: 'cert.pdf', displayLabel: 'cert.pdf' })) },
    ])
    const { user } = renderPage(<Harness initial={{ step: 'connect', kind: 'DOCUMENT', sourceId: null }} />)
    const drawer = await screen.findByRole('dialog')
    fireEvent.change(drawer.querySelector('input[type="file"]')!, { target: { files: [file('cert.pdf', 2048)] } })
    await user.click(within(drawer).getByRole('checkbox', { name: /may store and read this document/ }))
    await user.click(within(drawer).getByRole('button', { name: 'Upload and connect' }))
    await waitFor(() => expect(api.find('POST', `${SOURCES}/documents`)).toHaveLength(1))
    const form = api.find('POST', `${SOURCES}/documents`)[0].body as FormData
    expect((form.get('file') as File).name).toBe('cert.pdf')
    expect(form.get('consent')).toBe('true')
  })
})

describe('AddSourceDrawer: GitHub sign-in', () => {
  const GITHUB_CONSENT = /I agree that RecruitPro may read my public GitHub profile and repositories/

  it('switches to the username route when the server answers OAUTH_NOT_CONFIGURED', async () => {
    const api = mockApi([
      refreshRoute(),
      { path: SOURCES, reply: ok([]) },
      { path: '/candidates/me/verification/oauth/github/start', reply: fail(503, 'OAUTH_NOT_CONFIGURED: connecting GitHub is not configured on this server') },
    ])
    const { user } = renderPage(<Harness initial={{ step: 'connect', kind: 'GITHUB', sourceId: null }} />)
    const drawer = await screen.findByRole('dialog')
    await user.click(within(drawer).getByRole('button', { name: 'Continue with GitHub' }))
    expect(api.find('GET', '/candidates/me/verification/oauth/github/start')).toHaveLength(0)
    await user.click(within(drawer).getByRole('checkbox', { name: GITHUB_CONSENT }))
    await user.click(within(drawer).getByRole('button', { name: 'Continue with GitHub' }))
    expect(await within(drawer).findByText(/Signing in with GitHub is not available on this server/)).toBeInTheDocument()
    expect(within(drawer).getByLabelText('GitHub username')).toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: 'Sign in with GitHub instead' })).not.toBeInTheDocument()
  })

  it('treats any other 503 as an outage and keeps the sign-in route', async () => {
    mockApi([
      refreshRoute(),
      { path: SOURCES, reply: ok([]) },
      { path: '/candidates/me/verification/oauth/github/start', reply: { status: 503, raw: '' } },
    ])
    const { user } = renderPage(<Harness initial={{ step: 'connect', kind: 'GITHUB', sourceId: null }} />)
    const drawer = await screen.findByRole('dialog')
    await user.click(within(drawer).getByRole('checkbox', { name: GITHUB_CONSENT }))
    await user.click(within(drawer).getByRole('button', { name: 'Continue with GitHub' }))
    expect(await within(drawer).findByText('Something went wrong on our side. Please try again shortly.')).toBeInTheDocument()
    expect(within(drawer).queryByLabelText('GitHub username')).not.toBeInTheDocument()
    expect(within(drawer).getByRole('button', { name: 'Continue with GitHub' })).toBeInTheDocument()
  })

  it('refuses to leave for a non-https authorisation address', async () => {
    const assign = vi.fn()
    vi.stubGlobal('location', { ...window.location, assign })
    mockApi([
      refreshRoute(),
      { path: SOURCES, reply: ok([]) },
      { path: '/candidates/me/verification/oauth/github/start', reply: ok({ authorizeUrl: 'javascript:alert(1)' }) },
    ])
    const { user } = renderPage(<Harness initial={{ step: 'connect', kind: 'GITHUB', sourceId: null }} />)
    const drawer = await screen.findByRole('dialog')
    await user.click(within(drawer).getByRole('checkbox', { name: GITHUB_CONSENT }))
    await user.click(within(drawer).getByRole('button', { name: 'Continue with GitHub' }))
    expect(await within(drawer).findByText(/sign-in address from the server was not valid/)).toBeInTheDocument()
    expect(assign).not.toHaveBeenCalled()
  })
})

describe('AddSourceDrawer: URL state', () => {
  it('falls back to "choose" for step=connect without a kind', async () => {
    mockApi([refreshRoute(), { path: SOURCES, reply: ok([]) }])
    renderPage(<Harness initial={{ step: 'connect', kind: null, sourceId: null }} />)
    const drawer = await screen.findByRole('dialog', { name: 'Add a source' })
    expect(within(drawer).getByRole('radio', { name: /GitHub/ })).toBeChecked()
  })

  it('does not spin for ever when a run is answered as already finished', async () => {
    let proven = website({ ownershipStatus: 'PROVEN', ownershipMethod: 'DNS_TXT' })
    mockApi([
      refreshRoute(),
      { path: SOURCES, reply: () => ok([proven]) },
      {
        method: 'POST',
        path: `${SOURCES}/42/runs`,
        reply: () => {
          proven = { ...proven, lastRun: { id: 5, status: 'FAILED', finishedAt: '2026-11-16T10:00:01Z', errorCode: 'AGENT_SERVICE_UNAVAILABLE' } }
          return ok({ id: 5, sourceId: 42, status: 'FAILED', requestedBy: 'CANDIDATE', verifier: null, createdAt: '', startedAt: null, finishedAt: '', errorCode: 'AGENT_SERVICE_UNAVAILABLE' })
        },
      },
    ])
    const { user } = renderPage(<Harness initial={{ step: 'check', kind: 'WEBSITE', sourceId: 42 }} />)
    const drawer = await screen.findByRole('dialog')
    await user.click(await within(drawer).findByRole('button', { name: 'Run the first check' }))
    expect(await within(drawer).findByText('The verification service was unavailable. Please try again later.')).toBeInTheDocument()
    expect(within(drawer).queryByRole('progressbar')).not.toBeInTheDocument()
  })
})
