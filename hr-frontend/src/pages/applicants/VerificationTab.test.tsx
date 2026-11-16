import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { qk } from '../../api/queryKeys'
import type { StaffVerification } from '../../api/types'
import { verdict, verification } from '../../test/fixtures'
import { newQueryClient, renderApp } from '../../test/render'
import { VerificationView } from './VerificationTab'

vi.mock('../../api/verification', () => ({ verificationApi: { review: vi.fn(), forApplication: vi.fn() } }))
import { verificationApi } from '../../api/verification'

const evidence = [{ id: 1, key: 'E1', kind: 'COMMIT', url: 'https://github.com/asha-rao/payments/commit/1', title: 'asha-rao/payments', excerpt: 'retry logic', contentHash: 'sha256:abc', retrievedAt: '2026-11-15T10:00:00Z', attributes: {} }]

function show(data: StaffVerification, role: 'HR_MANAGER' | 'RECRUITER' | 'INTERVIEWER' = 'HR_MANAGER') {
  const queryClient = newQueryClient()
  queryClient.setQueryData(qk.applicantVerification(3001), data)
  return renderApp(<VerificationView jobId={101} applicationId={3001} data={data} />, { role, queryClient })
}

beforeEach(() => {
  vi.mocked(verificationApi.review).mockImplementation(async (id, body) => verdict(id, { myOrgReview: { decision: body.decision, note: body.note ?? null, reviewerName: 'Ria Reviewer', at: '2026-11-16T09:00:00Z' } }))
})

describe('verification tab', () => {
  it('shadow mode shows one neutral message and nothing else', () => {
    show({ mode: 'SHADOW', revealed: false, claims: [] })
    expect(screen.getByText(/shadow mode for your organisation/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Save review/ })).not.toBeInTheDocument()
  })

  it('blind: only the evidence count; no links, no domain, no candidate note — even if the server sent them', () => {
    const leaky = verification({
      revealed: false,
      claims: [{ subjectType: 'CANDIDATE_CLAIM', subjectId: 1, claimType: 'SKILL', value: 'Kafka', tier: 'SELF_CLAIMED', verdicts: [verdict(900, {
        evidence: evidence as never,
        candidateNote: 'See my repo asha-rao/payments',
        domainTrust: { check: 'EMPLOYER_DOMAIN', status: 'WEAK', trustedBy: null, domain: 'asharao.dev', reasons: [], candidateControlled: true, liftedByOrganization: false } as never,
      })] }],
    })
    show(leaky)
    expect(screen.getByText(/2 evidence items/)).toBeInTheDocument()
    expect(screen.queryByText(/asha-rao/)).not.toBeInTheDocument()
    expect(screen.queryByText('asharao.dev')).not.toBeInTheDocument()
    expect(document.body.innerHTML).not.toMatch(/asha-?rao/i)
  })

  it('revealed: evidence links and the candidate note are shown', () => {
    show(verification({ revealed: true, claims: [{ subjectType: 'CANDIDATE_CLAIM', subjectId: 1, claimType: 'SKILL', value: 'Kafka', tier: 'SELF_CLAIMED', verdicts: [verdict(900, { evidence: evidence as never, candidateNote: 'Built this at work' })] }] }))
    expect(screen.getByRole('link', { name: /asha-rao\/payments/ })).toHaveAttribute('href', 'https://github.com/asha-rao/payments/commit/1')
    expect(screen.getByText(/Built this at work/)).toBeInTheDocument()
  })

  it('overrule needs a note; confirm does not', async () => {
    show(verification())
    const form = screen.getByRole('button', { name: 'Save review' }).closest('form') as HTMLElement
    fireEvent.click(within(form).getByRole('button', { name: 'Save review' }))
    expect(await within(form).findByText('Choose Confirm or Overrule.')).toBeInTheDocument()
    fireEvent.click(within(form).getByLabelText('Overrule'))
    fireEvent.click(within(form).getByRole('button', { name: 'Save review' }))
    expect(await within(form).findByText(/Explain why you are overruling/)).toBeInTheDocument()
    expect(verificationApi.review).not.toHaveBeenCalled()
    fireEvent.change(within(form).getByRole('textbox'), { target: { value: 'The repo is a fork.' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save review' }))
    await waitFor(() => expect(verificationApi.review).toHaveBeenCalledWith(900, { decision: 'OVERRULE', note: 'The repo is a fork.' }))
    expect(await screen.findByText('Result overruled')).toBeInTheDocument()
  })

  it('confirm without a note is sent as is', async () => {
    show(verification())
    fireEvent.click(screen.getByLabelText('Confirm'))
    fireEvent.click(screen.getByRole('button', { name: 'Save review' }))
    await waitFor(() => expect(verificationApi.review).toHaveBeenCalledWith(900, { decision: 'CONFIRM', note: undefined }))
  })

  it('changing a review keeps the earlier note to edit', async () => {
    show(verification({ claims: [{ subjectType: 'CANDIDATE_CLAIM', subjectId: 1, claimType: 'SKILL', value: 'Kafka', tier: 'CORROBORATED', verdicts: [verdict(900, { myOrgReview: { decision: 'OVERRULE', note: 'Fork of another repo', reviewerName: 'Ria', at: '2026-11-16T09:00:00Z' } })] }] }))
    fireEvent.click(screen.getByRole('button', { name: 'Change review' }))
    expect(screen.getByRole('textbox')).toHaveValue('Fork of another repo')
  })
})
