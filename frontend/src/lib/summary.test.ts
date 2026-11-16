import { describe, expect, it } from 'vitest'
import { glanceCounts, pipelineCounts, stageWidths } from './summary'

const NOW = Date.parse('2026-11-17T10:00:00Z')

describe('glanceCounts', () => {
  it('counts open applications, tasks, results to review and strong claims', () => {
    const g = glanceCounts({
      now: NOW,
      applications: [{ status: 'SUBMITTED' }, { status: 'SHORTLISTED' }, { status: 'INTERVIEWING' }, { status: 'REJECTED' }, { status: 'WITHDRAWN' }, { status: 'SELECTED' }],
      samples: [{ status: 'ASSIGNED' }, { status: 'STARTED' }, { status: 'SUBMITTED' }, { status: 'EXPIRED' }],
      interviews: [
        { status: 'SCHEDULED', scheduledAt: '2026-11-18T10:00:00Z', durationMinutes: 45 }, // upcoming
        { status: 'SCHEDULED', scheduledAt: '2026-11-17T09:30:00Z', durationMinutes: 45 }, // under way: still to do
        { status: 'SCHEDULED', scheduledAt: '2026-11-16T09:00:00Z', durationMinutes: 45 }, // over
        { status: 'CANCELLED', scheduledAt: '2026-11-19T09:00:00Z', durationMinutes: 45 },
      ],
      ledger: [
        { tier: 'CORROBORATED', verdicts: [{ candidateStatus: 'SHARED' }] },
        { tier: 'INTERVIEW_VERIFIED', verdicts: [] },
        { tier: 'RESUME_STATED', verdicts: [{ candidateStatus: 'AWAITING_CANDIDATE' }, { candidateStatus: 'AWAITING_CANDIDATE' }] },
        { tier: 'SELF_CLAIMED', verdicts: [{ candidateStatus: 'HIDDEN' }] },
        { tier: null, verdicts: [] },
      ],
    })
    expect(g).toEqual({ inProgress: 3, toDo: 4, toReview: 2, strong: 2 })
  })

  it('is all zeros for a new account or while data is missing', () => {
    expect(glanceCounts({})).toEqual({ inProgress: 0, toDo: 0, toReview: 0, strong: 0 })
  })
})

describe('pipelineCounts and stageWidths', () => {
  it('groups statuses into stages in pipeline order', () => {
    const stages = pipelineCounts([{ status: 'SUBMITTED' }, { status: 'SUBMITTED' }, { status: 'SHORTLISTED' }, { status: 'REJECTED' }, { status: 'WITHDRAWN' }, { status: 'ODD' }])
    expect(stages.map(s => [s.key, s.count])).toEqual([
      ['review', 2],
      ['shortlisted', 1],
      ['interviewing', 0],
      ['selected', 0],
      ['closed', 2],
    ])
  })

  it('widths of the visible stages sum to exactly 100', () => {
    const w = stageWidths([{ count: 1 }, { count: 1 }, { count: 0 }, { count: 1 }])
    expect(w[2]).toBe(0)
    expect(w.reduce((a, b) => a + b, 0)).toBe(100)
    expect(w[3]).toBeCloseTo(33.333, 2)
    expect(stageWidths([{ count: 0 }, { count: 0 }])).toEqual([0, 0])
    expect(stageWidths([{ count: 0 }, { count: 5 }])).toEqual([0, 100])
  })
})
