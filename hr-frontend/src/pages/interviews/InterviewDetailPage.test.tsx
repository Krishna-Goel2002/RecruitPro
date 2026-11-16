import { fireEvent, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { InterviewDetail } from '../../api/types'
import { renderApp, testUser } from '../../test/render'
import { InterviewDetailPage } from './InterviewDetailPage'

vi.mock('../../api/assessments', () => ({ interviewsApi: { detail: vi.fn(), scorecardRevisions: vi.fn() } }))
import { interviewsApi } from '../../api/assessments'

const me = testUser('RECRUITER')
const scorecard = { ratings: [], recommendation: 'YES' as const, summary: 'Solid', submittedAt: '2026-11-10T10:00:00Z', revisionCount: 0 }

function interview(extra: Partial<InterviewDetail>): InterviewDetail {
  return {
    id: 4, applicationId: 3001, jobId: 12, jobTitle: 'Backend Engineer', candidateName: 'Candidate #A-3001', interviewerUserId: me.id,
    interviewerName: 'Ria Reviewer', scheduledAt: '2026-11-10T09:00:00Z', durationMinutes: 45, location: null, status: 'SCHEDULED',
    scorecard: null, kit: [], focusRequirements: [], ...extra,
  }
}

function show(data: InterviewDetail) {
  vi.mocked(interviewsApi.detail).mockResolvedValue(data)
  return renderApp(<InterviewDetailPage />, { role: 'RECRUITER', route: '/interviews/4', path: '/interviews/:interviewId' })
}

describe('scorecard editability comes from the server (R6-B4)', () => {
  it('offers Edit scorecard while it is editable', async () => {
    show(interview({ scorecard, applicationStatus: 'INTERVIEWING', scorecardEditable: true }))
    expect(await screen.findByRole('button', { name: 'Edit scorecard' })).toBeInTheDocument()
  })

  it('hides Edit scorecard once the application is decided and says why', async () => {
    show(interview({ scorecard, applicationStatus: 'REJECTED', scorecardEditable: false }))
    expect(await screen.findByText(/application was rejected, so the scorecard is locked/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit scorecard' })).not.toBeInTheDocument()
  })

  it('does not open the scorecard form when submitting is closed (withdrawn)', async () => {
    show(interview({ applicationStatus: 'WITHDRAWN', scorecardEditable: false }))
    expect(await screen.findByText(/withdrew the application/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Submit scorecard' })).not.toBeInTheDocument()
    expect(screen.getByText('Scorecard closed')).toBeInTheDocument()
  })

  it('another interviewer never gets the form, even when editable', async () => {
    show(interview({ interviewerUserId: 999, applicationStatus: 'INTERVIEWING', scorecardEditable: true }))
    expect(await screen.findByText(/has not submitted a scorecard yet/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Submit scorecard' })).not.toBeInTheDocument()
  })
})

describe('earlier versions of an edited scorecard (completeness sweep)', () => {
  it('the author can open the earlier versions the server keeps', async () => {
    vi.mocked(interviewsApi.scorecardRevisions).mockResolvedValue([
      { id: 1, revisedByUserId: me.id, revisedByName: 'Ria Reviewer', revisedAt: '2026-11-11T10:00:00Z', previous: { ratings: [], recommendation: 'NO', summary: 'First take', submittedAt: '2026-11-10T10:00:00Z' } },
    ])
    show(interview({ scorecard: { ...scorecard, revisionCount: 1 }, applicationStatus: 'INTERVIEWING', scorecardEditable: true }))
    fireEvent.click(await screen.findByRole('button', { name: 'Show the earlier version' }))
    expect(await screen.findByText('First take')).toBeInTheDocument()
    expect(screen.getByText(/by Ria Reviewer/)).toBeInTheDocument()
    expect(interviewsApi.scorecardRevisions).toHaveBeenCalledWith(4, expect.anything())
  })

  it('an unedited scorecard offers nothing', async () => {
    show(interview({ scorecard, applicationStatus: 'INTERVIEWING', scorecardEditable: true }))
    await screen.findByRole('button', { name: 'Edit scorecard' })
    expect(screen.queryByRole('button', { name: /earlier version/ })).not.toBeInTheDocument()
  })
})
