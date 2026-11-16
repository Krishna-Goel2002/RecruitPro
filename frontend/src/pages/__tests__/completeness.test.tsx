/**
 * Completeness sweep: every draft can be deleted, every correction undone, and pages that hold unsent input warn
 * before a reload or a closed tab loses it.
 */
import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { CandidateWorkSample, JobPublic, PublicReference, Resume, ResumeAnalysis } from '../../api/types'
import { useScrollToHash } from '../../lib/hooks'
import { mockApi, ok, refreshRoute, renderPage } from '../../test/utils'
import { EvidencePage } from '../EvidencePage'
import { ApplyDialog } from '../jobs/ApplyDialog'
import { RefereePage } from '../RefereePage'
import { ResumesPage } from '../ResumesPage'
import { WorkSampleResponsePage, discardSummary } from '../WorkSampleResponsePage'

const LIST = '/candidates/me/work-samples'
const TEXT_KEY = 'draft-work-sample-3'
const URL_KEY = 'draft-work-sample-url-3'

function sample(extra: Partial<CandidateWorkSample> = {}): CandidateWorkSample {
  return {
    assignmentId: 3, jobTitle: 'Backend Engineer', title: 'Refunds API', instructions: 'Design an idempotent refunds endpoint.', timeLimitMinutes: 90,
    status: 'STARTED', dueAt: null, startedAt: new Date(Date.now() - 10 * 60_000).toISOString(), submittedAt: null,
    deadlineAt: new Date(Date.now() + 80 * 60_000).toISOString(), files: [], cancelledAt: null, cancelReason: null, ...extra,
  }
}

function renderSample(current: CandidateWorkSample) {
  mockApi([refreshRoute(), { path: LIST, reply: ok([current]) }])
  return renderPage(<WorkSampleResponsePage />, { route: '/work-samples/3', path: '/work-samples/:assignmentId' })
}

/** True when a beforeunload listener asked the browser to warn. */
function unloadIsBlocked(): boolean {
  const event = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(event)
  return event.defaultPrevented
}

describe('work sample drafts', () => {
  it('discards the running draft (text and link) after a confirmation', async () => {
    const { user } = renderSample(sample())
    await screen.findByText('Design an idempotent refunds endpoint.')
    expect(screen.queryByRole('button', { name: 'Discard draft' })).not.toBeInTheDocument()

    await user.type(screen.getByLabelText(/^Response/), 'First attempt')
    await user.type(screen.getByLabelText(/Link to your work/), 'https://docs.example/a')
    await waitFor(() => expect(window.localStorage.getItem(URL_KEY)).toBe('https://docs.example/a'))

    await user.click(screen.getByRole('button', { name: 'Discard draft' }))
    const confirm = await screen.findByRole('alertdialog', { name: 'Discard your draft?' })
    expect(within(confirm).getByText(/Your response text and the link are cleared/)).toBeInTheDocument()
    // Destructive: Cancel comes first and has focus.
    expect(within(confirm).getByRole('button', { name: 'Cancel' })).toHaveFocus()
    await user.click(within(confirm).getByRole('button', { name: 'Discard draft' }))

    expect(screen.getByLabelText(/^Response/)).toHaveValue('')
    expect(screen.getByLabelText(/Link to your work/)).toHaveValue('')
    expect(window.localStorage.getItem(TEXT_KEY)).toBeNull()
    expect(window.localStorage.getItem(URL_KEY)).toBeNull()
    expect(await screen.findByText('Draft discarded')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Discard draft' })).not.toBeInTheDocument()
  })

  it('words the discard confirmation for what is there', () => {
    expect(discardSummary('a', '', 0)).toBe('Your response text is cleared from this page and this browser.')
    expect(discardSummary('', '', 2)).toBe('2 attached files are cleared from this page and this browser.')
    expect(discardSummary('a', 'https://x.y', 1)).toBe('Your response text, the link and 1 attached file are cleared from this page and this browser.')
  })

  it('restores the link with the text after a reload', async () => {
    window.localStorage.setItem(TEXT_KEY, 'Saved answer')
    window.localStorage.setItem(URL_KEY, 'https://docs.example/saved')
    renderSample(sample())
    await screen.findByText('Design an idempotent refunds endpoint.')
    expect(screen.getByLabelText(/^Response/)).toHaveValue('Saved answer')
    expect(screen.getByLabelText(/Link to your work/)).toHaveValue('https://docs.example/saved')
  })

  it.each([
    ['expired', sample({ status: 'EXPIRED' })],
    ['cancelled', sample({ status: 'CANCELLED', cancelledAt: new Date().toISOString() })],
  ])('shows an unsent draft of a %s task and deletes it after a confirmation', async (_label, current) => {
    window.localStorage.setItem(TEXT_KEY, 'Never sent')
    window.localStorage.setItem(URL_KEY, 'https://docs.example/never')
    const { user } = renderSample(current)
    expect(await screen.findByText('Your unsent response')).toBeInTheDocument()
    expect(screen.getByText('Never sent')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Delete draft' }))
    const confirm = await screen.findByRole('alertdialog', { name: 'Delete your unsent response?' })
    await user.click(within(confirm).getByRole('button', { name: 'Delete draft' }))

    await waitFor(() => expect(screen.queryByText('Your unsent response')).not.toBeInTheDocument())
    expect(window.localStorage.getItem(TEXT_KEY)).toBeNull()
    expect(window.localStorage.getItem(URL_KEY)).toBeNull()
  })

  it('drops a stale local draft once the response was submitted', async () => {
    window.localStorage.setItem(TEXT_KEY, 'Typed in another tab')
    renderSample(sample({ status: 'SUBMITTED', submittedAt: new Date().toISOString() }))
    expect(await screen.findByText(/^Submitted \w/)).toBeInTheDocument()
    await waitFor(() => expect(window.localStorage.getItem(TEXT_KEY)).toBeNull())
  })

  it('links the job title to its application', async () => {
    renderSample(sample({ applicationId: 3001 }))
    await screen.findByText('Design an idempotent refunds endpoint.')
    expect(screen.getByRole('link', { name: 'Backend Engineer' })).toHaveAttribute('href', '/applications/3001')
  })
})

describe('apply dialog cover letter', () => {
  const job = {
    id: 101, organizationName: 'Acme Payments', title: 'Backend Engineer', description: '', department: null, location: 'Pune', jobType: 'FULL_TIME',
    experienceLevel: 'MID', minimumSalary: null, maximumSalary: null, vacancies: 1, deadline: null, publishedAt: null, requirements: [],
  } as unknown as JobPublic
  const resume: Resume = { id: 2, originalFilename: 'cv-2026.pdf', contentType: 'application/pdf', fileSize: 2000, uploadedAt: '2026-11-01T00:00:00Z' }

  it('clears a restored draft, with Undo', async () => {
    window.localStorage.setItem('draft-cover-letter-101', 'An old letter for another role')
    mockApi([refreshRoute(), { path: '/candidates/me/resumes', reply: ok([resume]) }, { path: '/candidates/me/verification/ledger', reply: ok([]) }])
    const { user } = renderPage(<ApplyDialog job={job} matches={[]} open onClose={vi.fn()} />)
    const dialog = await screen.findByRole('dialog', { name: 'Apply: Backend Engineer' })
    const field = within(dialog).getByLabelText(/Cover letter/)
    expect(field).toHaveValue('An old letter for another role')

    await user.click(within(dialog).getByRole('button', { name: 'Clear cover letter' }))
    expect(field).toHaveValue('')
    expect(window.localStorage.getItem('draft-cover-letter-101')).toBeNull()

    await user.click(await screen.findByRole('button', { name: 'Undo' }))
    expect(field).toHaveValue('An old letter for another role')
  })
})

describe('resume corrections', () => {
  const resume: Resume = { id: 5, originalFilename: 'meera-cv.pdf', contentType: 'application/pdf', fileSize: 120_000, uploadedAt: '2026-11-15T10:00:00Z' }
  const analysis: ResumeAnalysis = {
    resumeId: 5, status: 'COMPLETED', parserVersion: '2', errorCode: null, attemptCount: 1, analyzedAt: '2026-11-15T10:01:00Z', flags: [],
    facts: [{ id: 51, type: 'SKILL', value: 'PostgreSQL', extractedValue: 'Postgres', evidence: null, sourceSection: 'SKILLS', tier: 'RESUME_STATED', claimedValue: 'PostgreSQL', claimId: 9 }],
  }

  it('removes a correction in one click (it used to require retyping the text as read)', async () => {
    const api = mockApi([
      refreshRoute(),
      { path: '/candidates/me/resumes', reply: ok([resume]) },
      { path: '/candidates/me/resumes/5/analysis', reply: ok(analysis) },
      { method: 'PATCH', path: '/candidates/me/resumes/5/analysis/facts/51', reply: ok({ ...analysis.facts[0], value: 'Postgres', claimedValue: null, claimId: null }) },
    ])
    const { user } = renderPage(<ResumesPage />, { route: '/resumes', path: '/resumes' })
    await user.click(await screen.findByRole('button', { name: 'Remove my correction for Postgres' }))
    expect(await screen.findByText('Correction removed')).toBeInTheDocument()
    expect(api.find('PATCH', '/candidates/me/resumes/5/analysis/facts/51')[0].body).toEqual({ value: 'Postgres' })
  })

  it('names a long file name in full on hover', async () => {
    mockApi([refreshRoute(), { path: '/candidates/me/resumes', reply: ok([resume]) }, { path: '/candidates/me/resumes/5/analysis', reply: ok(analysis) }])
    renderPage(<ResumesPage />, { route: '/resumes', path: '/resumes' })
    const list = await screen.findByRole('navigation', { name: 'Your resumes' })
    expect(within(list).getByTitle('meera-cv.pdf')).toBeInTheDocument()
  })
})

describe('add a claim drawer', () => {
  it('asks before throwing away a half-written claim, and closes at once when nothing was typed', async () => {
    mockApi([refreshRoute(), { path: '/candidates/me/claims', reply: ok([]) }])
    const { user } = renderPage(<EvidencePage />, { route: '/evidence', path: '/evidence' })
    await user.click((await screen.findAllByRole('button', { name: 'Add a claim' }))[0])
    let drawer = await screen.findByRole('dialog', { name: 'Add a claim' })
    await user.click(within(drawer).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Add a claim' })).not.toBeInTheDocument())
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()

    await user.click(screen.getAllByRole('button', { name: 'Add a claim' })[0])
    drawer = await screen.findByRole('dialog', { name: 'Add a claim' })
    await user.type(within(drawer).getByLabelText(/What you are claiming/), 'Tally ERP, 3 years')
    await user.click(within(drawer).getByRole('button', { name: 'Cancel' }))
    const confirm = await screen.findByRole('alertdialog', { name: 'Discard this claim?' })
    await user.click(within(confirm).getByRole('button', { name: 'Cancel' }))
    // Kept: the drawer and the text are still there.
    expect(within(screen.getByRole('dialog', { name: 'Add a claim' })).getByLabelText(/What you are claiming/)).toHaveValue('Tally ERP, 3 years')

    await user.click(within(screen.getByRole('dialog', { name: 'Add a claim' })).getByRole('button', { name: 'Cancel' }))
    await user.click(within(await screen.findByRole('alertdialog', { name: 'Discard this claim?' })).getByRole('button', { name: 'Discard claim' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Add a claim' })).not.toBeInTheDocument())
  })
})

describe('unsent answers on the referee page', () => {
  const reference: PublicReference = {
    candidateFirstName: 'Meera', relationship: 'MANAGER', employerName: 'Acme Payments',
    claims: [{ id: 'fact:12', claimType: 'SKILL', value: 'PostgreSQL query tuning' }], expiresAt: '2026-11-30T10:00:00Z',
  }

  it('warns before leaving only once something was answered', async () => {
    mockApi([{ path: '/public/references/tok', reply: ok(reference) }])
    const { user } = renderPage(<RefereePage />, { route: '/reference/tok', path: '/reference/:token', auth: false })
    await screen.findByRole('heading', { level: 1 })
    expect(unloadIsBlocked()).toBe(false)
    await user.click(within(screen.getByRole('radiogroup', { name: 'Your answer for PostgreSQL query tuning' })).getByLabelText('Confirm'))
    expect(unloadIsBlocked()).toBe(true)
  })
})

describe('useScrollToHash', () => {
  function Target({ ready }: { ready: boolean }) {
    useScrollToHash(ready)
    return (
      <section id="references">
        <h2>References</h2>
      </section>
    )
  }

  it('scrolls to and focuses the section named by the hash once the page is ready', async () => {
    const scroll = vi.spyOn(Element.prototype, 'scrollIntoView')
    const { rerender } = render(
      <MemoryRouter initialEntries={['/verification#references']}>
        <Target ready={false} />
      </MemoryRouter>,
    )
    await new Promise(resolve => window.setTimeout(resolve, 80))
    expect(scroll).not.toHaveBeenCalled()
    rerender(
      <MemoryRouter initialEntries={['/verification#references']}>
        <Target ready />
      </MemoryRouter>,
    )
    await waitFor(() => expect(scroll).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('heading', { name: 'References' })).toHaveFocus()
  })
})
