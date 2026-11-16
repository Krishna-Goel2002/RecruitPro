import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { ActionBlock, Block } from '../api/assistantTypes'
import { Blocks, effectiveStatus, remaining, type BlockHandlers } from './blocks'
import { MarkdownLite, safeHref } from './markdown'

function handlers(over: Partial<BlockHandlers> = {}): BlockHandlers {
  return { interactive: true, canAct: true, busyAction: null, onAnswer: vi.fn(), onConfirm: vi.fn(), onCancel: vi.fn(), ...over }
}
const show = (blocks: Block[], h = handlers()) => {
  render(<MemoryRouter><Blocks blocks={blocks} handlers={h} /></MemoryRouter>)
  return h
}
const inAMinute = () => new Date(Date.now() + 29 * 60_000 + 30_000).toISOString()
const action = (over: Partial<ActionBlock> = {}): ActionBlock => ({
  kind: 'action', actionId: 77, action: 'decide_application', summary: 'Shortlist A-3014 for Senior Backend Engineer',
  preview: { application: 'A-3014', from: 'Submitted', to: 'Shortlisted' }, status: 'PENDING', expiresAt: inAMinute(), ...over,
})

describe('metrics block', () => {
  it('shows each label and value (numbers in the accessible text even with count-up)', () => {
    show([{ kind: 'metrics', items: [{ label: 'Applicants', value: 38, hint: '4 new' }, { label: 'Median verified', value: '62%' }] }])
    expect(screen.getByText('Applicants')).toBeInTheDocument()
    expect(screen.getAllByText('38').length).toBeGreaterThan(0)
    expect(screen.getByText('4 new')).toBeInTheDocument()
    expect(screen.getByText('62%')).toBeInTheDocument()
  })
})

describe('table block', () => {
  it('renders columns and rows and sorts by a column', () => {
    show([{ kind: 'table', caption: 'Most-missing requirements', columns: ['Requirement', { label: 'Missing', numeric: true }], rows: [['Kafka', 3], ['Kubernetes', 9], ['AWS', 5]] }])
    const table = screen.getByRole('table', { name: 'Most-missing requirements' })
    const first = () => within(table).getAllByRole('row')[1]
    expect(first()).toHaveTextContent('Kafka')
    fireEvent.click(within(table).getByRole('button', { name: /Missing/ }))
    expect(first()).toHaveTextContent('Kubernetes')
    expect(within(table).getByRole('columnheader', { name: /Missing/ })).toHaveAttribute('aria-sort', 'descending')
    fireEvent.click(within(table).getByRole('button', { name: /Missing/ }))
    expect(first()).toHaveTextContent('Kafka')
  })
})

describe('candidates block', () => {
  it('shows the code when blind, the name when revealed, status, group, gates and a link', () => {
    show([{
      kind: 'candidates',
      items: [
        { applicationId: 3014, code: 'A-3014', revealedName: null, status: 'SUBMITTED', verified: 74, claimed: 82, group: 'RANKED', gates: true, link: '/jobs/101/applicants/3014' },
        { applicationId: 3001, code: 'A-3001', revealedName: 'Meera Iyer', status: 'INTERVIEWING', verified: 86, claimed: 86, group: 'KNOCKOUT_UNVERIFIED', gates: { open: 1, total: 2 }, link: 'javascript:alert(1)' },
      ],
    }])
    const blind = screen.getByRole('link', { name: /A-3014/ })
    expect(blind).toHaveAttribute('href', '/jobs/101/applicants/3014')
    expect(blind).toHaveTextContent('Blind')
    expect(screen.getByText('Meera Iyer')).toBeInTheDocument()
    // An unsafe link is never rendered as a link.
    expect(screen.queryByRole('link', { name: /Meera/ })).toBeNull()
    expect(screen.getByText('Gates verified')).toBeInTheDocument()
    expect(screen.getByText('1 of 2 gates open')).toBeInTheDocument()
  })
})

describe('job_preview block: Save as draft vs Publish', () => {
  const preview: Block = {
    kind: 'job_preview',
    draft: {
      title: 'Senior Data Analyst', department: 'Analytics', location: 'Pune', jobType: 'FULL_TIME', experienceLevel: 'SENIOR', openings: 2,
      summary: 'Own the reporting layer.', responsibilities: ['Build dashboards', 'Work with finance'],
      requirements: [
        { type: 'SKILL', description: 'SQL', mandatory: true, knockout: true, minimumYears: 4 },
        { type: 'SKILL', description: 'dbt', mandatory: false, alternatives: ['Dataform'] },
      ],
      warnings: [{ phrase: 'rockstar', text: '“Rockstar” can put off some applicants.', suggestion: 'experienced analyst' }],
    },
  }
  const create = action({ actionId: 'a-9', action: 'create_job', summary: 'Create “Senior Data Analyst”', preview: null })

  it('shows the full posting with requirement chips and warnings, and hides the duplicate action card', () => {
    show([preview, create])
    expect(screen.getByRole('heading', { name: 'Senior Data Analyst' })).toBeInTheDocument()
    expect(screen.getByText('Must-have')).toBeInTheDocument()
    expect(screen.getByText('Gate')).toBeInTheDocument()
    expect(screen.getByText('Nice to have')).toBeInTheDocument()
    expect(screen.getByText(/4\+ years/)).toBeInTheDocument()
    expect(screen.getByText(/also accepts Dataform/)).toBeInTheDocument()
    expect(screen.getByText(/Rockstar/)).toBeInTheDocument()
    expect(screen.queryByRole('article', { name: /Proposed action/ })).toBeNull()
  })

  it('Save as draft confirms the create_job action with mode DRAFT', () => {
    const h = show([preview, create])
    fireEvent.click(screen.getByRole('button', { name: 'Save as draft' }))
    expect(h.onConfirm).toHaveBeenCalledWith(expect.objectContaining({ actionId: 'a-9' }), 'DRAFT')
  })

  it('Publish asks once more, then confirms with mode PUBLISH', () => {
    const h = show([preview, create])
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }))
    expect(h.onConfirm).not.toHaveBeenCalled()
    expect(screen.getByText(/Candidates can see and apply/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Publish now' }))
    expect(h.onConfirm).toHaveBeenCalledWith(expect.objectContaining({ actionId: 'a-9' }), 'PUBLISH')
  })

  it('"Not yet" backs out without confirming; Discard cancels the action', () => {
    const h = show([preview, create])
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }))
    fireEvent.click(screen.getByRole('button', { name: 'Not yet' }))
    expect(h.onConfirm).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Discard' }))
    expect(h.onCancel).toHaveBeenCalledWith(expect.objectContaining({ actionId: 'a-9' }))
  })

  it('once confirmed it shows the outcome and a link to the job', () => {
    show([preview, { ...create, status: 'CONFIRMED', result: { kind: 'job', id: 55, link: '/jobs/55' } }])
    expect(screen.queryByRole('button', { name: 'Publish' })).toBeNull()
    expect(screen.getByRole('link', { name: /Open the job/ })).toHaveAttribute('href', '/jobs/55')
  })
})

describe('action block', () => {
  it('PENDING: preview, expiry countdown, Confirm and Cancel', () => {
    const h = show([action()])
    expect(screen.getByText('Shortlist A-3014 for Senior Backend Engineer')).toBeInTheDocument()
    expect(screen.getByText('Waiting for your confirmation')).toBeInTheDocument()
    expect(screen.getByText('Submitted')).toBeInTheDocument()
    expect(screen.getByText(/Expires in/)).toHaveTextContent(/29:\d\d/)
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(h.onConfirm).toHaveBeenCalledWith(expect.objectContaining({ actionId: 77 }))
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(h.onCancel).toHaveBeenCalledWith(expect.objectContaining({ actionId: 77 }))
  })

  it.each([
    ['CONFIRMED', 'Done'],
    ['CANCELLED', 'Cancelled'],
    ['EXPIRED', 'Expired'],
    ['FAILED', 'Failed'],
  ] as const)('%s shows its state and no buttons', (status, label) => {
    show([action({ status, error: status === 'FAILED' ? 'The application was withdrawn.' : null })])
    expect(screen.getByText(label)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirm' })).toBeNull()
  })

  it('a PENDING action past its expiry is shown as expired', () => {
    const past = action({ expiresAt: new Date(Date.now() - 1000).toISOString() })
    expect(effectiveStatus(past)).toBe('EXPIRED')
    show([past])
    expect(screen.getByText('Expired')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirm' })).toBeNull()
  })

  it('formats the countdown', () => {
    const now = Date.parse('2026-11-17T10:00:00Z')
    expect(remaining('2026-11-17T10:29:05Z', now)).toBe('29:05')
    expect(remaining('2026-11-17T11:30:00Z', now)).toBe('1 h 30 min')
    expect(remaining('2026-11-17T09:00:00Z', now)).toBeNull()
  })
})

describe('question, link and notice blocks', () => {
  it('question options send the answer; disabled when not the latest message', () => {
    const h = show([{ kind: 'question', field: 'location', text: 'Which location?', options: ['Pune', 'Remote'] }])
    fireEvent.click(screen.getByRole('button', { name: 'Remote' }))
    expect(h.onAnswer).toHaveBeenCalledWith('Remote')
  })

  it('question chips are disabled in older messages', () => {
    show([{ kind: 'question', field: 'location', text: 'Which location?', options: ['Pune'] }], handlers({ interactive: false }))
    expect(screen.getByRole('button', { name: 'Pune' })).toBeDisabled()
  })

  it('links: internal via the router, http(s) in a new tab, anything else as text', () => {
    show([
      { kind: 'link', label: 'Fairness report', href: '/jobs/101?tab=fairness' },
      { kind: 'link', label: 'Help centre', href: 'https://help.example.com/coverage' },
      { kind: 'link', label: 'Bad', href: 'javascript:alert(1)' },
    ])
    expect(screen.getByRole('link', { name: /Fairness report/ })).toHaveAttribute('href', '/jobs/101?tab=fairness')
    const ext = screen.getByRole('link', { name: /Help centre/ })
    expect(ext).toHaveAttribute('target', '_blank')
    expect(ext).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.queryByRole('link', { name: /Bad/ })).toBeNull()
    expect(screen.getByText('Bad')).toBeInTheDocument()
  })

  it('notice renders as an alert in its tone', () => {
    const { container } = render(<MemoryRouter><Blocks blocks={[{ kind: 'notice', tone: 'warning', text: 'Only offline rules are used.' }]} handlers={handlers()} /></MemoryRouter>)
    expect(screen.getByText('Only offline rules are used.')).toBeInTheDocument()
    expect(container.querySelector('.ui-alert')).toHaveAttribute('data-tone', 'warning')
  })
})

describe('markdown-lite', () => {
  it('bold, lists, code and safe links; markup and unsafe links stay text', () => {
    render(
      <MemoryRouter>
        <MarkdownLite text={'**5 applicants** need review:\n- A-3014\n- A-3022\n\nSee [the job](/jobs/101) or [docs](https://example.com). `x` <b>no</b> [evil](javascript:alert(1))'} />
      </MemoryRouter>,
    )
    expect(screen.getByText('5 applicants').tagName).toBe('STRONG')
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    expect(screen.getByRole('link', { name: 'the job' })).toHaveAttribute('href', '/jobs/101')
    expect(screen.getByRole('link', { name: 'docs' })).toHaveAttribute('href', 'https://example.com/')
    expect(screen.queryByRole('link', { name: 'evil' })).toBeNull()
    expect(screen.getByText(/<b>no<\/b>/)).toBeInTheDocument()
    expect(document.querySelector('b')).toBeNull()
  })

  it('safeHref allows only app paths and http(s)', () => {
    expect(safeHref('/jobs/1')).toEqual({ kind: 'internal', to: '/jobs/1' })
    expect(safeHref('//evil.example')).toBeNull()
    expect(safeHref('data:text/html,hi')).toBeNull()
    expect(safeHref('mailto:a@b.c')).toBeNull()
    expect(safeHref('https://ok.example/x')).toEqual({ kind: 'external', href: 'https://ok.example/x' })
  })
})
