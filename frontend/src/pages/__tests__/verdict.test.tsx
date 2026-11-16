import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { queryKeys } from '../../api/queryKeys'
import type { LedgerClaim, VerdictDetail } from '../../api/types'
import { fail, mockApi, ok, refreshRoute, renderPage } from '../../test/utils'
import { VerdictDrawer } from '../verification/VerdictDrawer'

const V = '/candidates/me/verification/verdicts'

function detail(id: number, extra: Partial<VerdictDetail> = {}): VerdictDetail {
  return {
    id, subjectType: 'RESUME_FACT', subjectId: id * 10, claimType: 'SKILL', value: id === 31 ? 'PostgreSQL' : 'Kubernetes', verifier: 'code-verifier',
    verifierVersion: '1', verdict: 'PARTLY_VERIFIED', ownershipProven: true, candidateStatus: 'AWAITING_CANDIDATE', candidateNote: null,
    candidateRespondedAt: null, current: true, sourceId: 42, sourceKind: 'GITHUB', sourceLabel: 'github.com/meera', referenceId: null, runId: 5,
    summary: [], reasons: [{ text: 'Used in two repositories.', evidenceKeys: ['e1'], quote: 'import psycopg' }], integrityNotes: [],
    probeQuestion: 'How did you tune the slow query?', createdAt: '2026-11-16T10:00:00Z',
    evidence: [
      { id: 1, key: 'e1', kind: 'REPOSITORY', url: 'https://github.com/meera/payments', title: 'meera/payments', retrievedAt: '2026-11-16T10:00:00Z', contentHash: 'sha256:abcdef0123456789abcdef', excerpt: null, attributes: null },
      { id: 2, key: 'e2', kind: 'PAGE', url: 'javascript:alert(document.cookie)', title: 'Injected', retrievedAt: '2026-11-16T10:00:00Z', contentHash: 'sha256:00', excerpt: null, attributes: null },
    ],
    ...extra,
  }
}

const ledger: LedgerClaim[] = [
  { subjectType: 'RESUME_FACT', subjectId: 310, claimType: 'SKILL', value: 'PostgreSQL', tier: 'RESUME_STATED', verdicts: [{ id: 31, verifier: 'code-verifier', verdict: 'PARTLY_VERIFIED', candidateStatus: 'AWAITING_CANDIDATE', sourceLabel: 'github.com/meera', createdAt: '2026-11-16T10:00:00Z' }] },
  { subjectType: 'RESUME_FACT', subjectId: 320, claimType: 'SKILL', value: 'Kubernetes', tier: 'RESUME_STATED', verdicts: [{ id: 32, verifier: 'code-verifier', verdict: 'NOT_FOUND', candidateStatus: 'AWAITING_CANDIDATE', sourceLabel: 'github.com/meera', createdAt: '2026-11-16T10:00:00Z' }] },
]

let ledgerProbe: () => LedgerClaim[] | undefined = () => undefined

function Harness({ onNavigate }: { onNavigate: (id: number | null) => void }) {
  const queryClient = useQueryClient()
  const [id, setId] = useState<number | null>(31)
  useState(() => queryClient.setQueryData(queryKeys.verificationLedger, ledger))
  ledgerProbe = () => queryClient.getQueryData<LedgerClaim[]>(queryKeys.verificationLedger)
  return (
    <VerdictDrawer
      verdictId={id}
      queue={[31, 32]}
      onNavigate={next => {
        onNavigate(next)
        setId(next)
      }}
      onClose={() => setId(null)}
    />
  )
}

const statusOf = (id: number) => ledgerProbe()?.flatMap(c => c.verdicts).find(v => v.id === id)?.candidateStatus

describe('VerdictDrawer', () => {
  it('renders evidence links only for http(s) URLs', async () => {
    mockApi([refreshRoute(), { path: `${V}/31`, reply: ok(detail(31)) }, { path: '/candidates/me/verification/ledger', reply: ok(ledger) }])
    renderPage(<Harness onNavigate={() => undefined} />)
    const drawer = await screen.findByRole('dialog', { name: 'PostgreSQL' })
    await within(drawer).findByText('meera/payments')
    const links = within(drawer).getAllByRole('link', { name: /View source/ })
    expect(links).toHaveLength(1)
    expect(links[0]).toHaveAttribute('href', 'https://github.com/meera/payments')
    expect(drawer.querySelector('a[href^="javascript"]')).toBeNull()
  })

  it('shares optimistically, sends the note, and moves on to the next result', async () => {
    let release: () => void = () => undefined
    const api = mockApi([
      refreshRoute(),
      { path: `${V}/31`, reply: ok(detail(31)) },
      { path: `${V}/32`, reply: ok(detail(32)) },
      { path: '/candidates/me/verification/ledger', reply: ok(ledger) },
      {
        method: 'POST',
        path: `${V}/31/response`,
        reply: () => new Promise(resolve => (release = () => resolve(ok(detail(31, { candidateStatus: 'SHARED', candidateNote: 'Old repo' }))))),
      },
    ])
    const onNavigate = vi.fn()
    const { user } = renderPage(<Harness onNavigate={onNavigate} />)
    const drawer = await screen.findByRole('dialog', { name: 'PostgreSQL' })
    await within(drawer).findByText('Used in two repositories.')
    await user.click(within(drawer).getByRole('button', { name: 'Add a note for employers' }))
    await user.type(within(drawer).getByLabelText(/Note for employers/), 'Old repo')
    await user.click(within(drawer).getByRole('button', { name: 'Share with employers' }))
    // Before the server answers, the ledger already says SHARED.
    await waitFor(() => expect(statusOf(31)).toBe('SHARED'))
    expect(api.find('POST', `${V}/31/response`)[0].body).toEqual({ action: 'SHARE', note: 'Old repo' })
    release()
    await waitFor(() => expect(onNavigate).toHaveBeenCalledWith(32))
    expect(await screen.findByRole('dialog', { name: 'Kubernetes' })).toBeInTheDocument()
  })

  it('rolls the optimistic status back when the server refuses', async () => {
    mockApi([
      refreshRoute(),
      { path: `${V}/31`, reply: ok(detail(31)) },
      { path: '/candidates/me/verification/ledger', reply: ok(ledger) },
      { method: 'POST', path: `${V}/31/response`, reply: fail(400, 'This result was replaced or withdrawn; respond to the current one') },
    ])
    const onNavigate = vi.fn()
    const { user } = renderPage(<Harness onNavigate={onNavigate} />)
    const drawer = await screen.findByRole('dialog', { name: 'PostgreSQL' })
    await within(drawer).findByText('Used in two repositories.')
    await user.click(within(drawer).getByRole('button', { name: 'Hide' }))
    expect(await within(drawer).findByText('This result was replaced or withdrawn; respond to the current one')).toBeInTheDocument()
    expect(statusOf(31)).toBe('AWAITING_CANDIDATE')
    expect(onNavigate).not.toHaveBeenCalled()
  })

  it('moves with J and K, but not with Ctrl+K or while typing', async () => {
    mockApi([refreshRoute(), { path: `${V}/31`, reply: ok(detail(31)) }, { path: `${V}/32`, reply: ok(detail(32)) }, { path: '/candidates/me/verification/ledger', reply: ok(ledger) }])
    const onNavigate = vi.fn()
    const { user } = renderPage(<Harness onNavigate={onNavigate} />)
    const drawer = await screen.findByRole('dialog', { name: 'PostgreSQL' })
    await within(drawer).findByText('Used in two repositories.')
    await user.keyboard('{Control>}j{/Control}')
    expect(onNavigate).not.toHaveBeenCalled()
    await user.click(within(drawer).getByRole('button', { name: 'Add a note for employers' }))
    await user.keyboard('j')
    expect(onNavigate).not.toHaveBeenCalled()
    within(drawer).getByLabelText(/Note for employers/).blur()
    await user.keyboard('j')
    expect(onNavigate).toHaveBeenCalledWith(32)
    await screen.findByRole('dialog', { name: 'Kubernetes' })
    await user.keyboard('k')
    expect(onNavigate).toHaveBeenLastCalledWith(31)
  })
})
