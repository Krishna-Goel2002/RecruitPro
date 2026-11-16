/**
 * "What happens next" for an application, from its status plus the candidate's work samples and interviews.
 * The list endpoints carry no application id, so tasks are joined by job title (API gap, FLOWS-CANDIDATE §14.1);
 * the application detail has exact lists and passes them directly.
 */
import type { ApplicationInterview, ApplicationStatus, CandidateInterview, CandidateWorkSample } from '../api/types'
import { formatDate } from '../design/lib/format'

export type NextStepKind = 'work-sample' | 'work-sample-started' | 'interview' | 'waiting' | 'selected' | 'closed'

export interface NextStep {
  kind: NextStepKind
  /** One short line for tables and lists. */
  label: string
  /** Where the action is. */
  to?: string
  /** For urgent ordering on the Overview. */
  at?: string | null
  sample?: CandidateWorkSample
  interview?: ApplicationInterview | CandidateInterview
}

const ACTIVE: ApplicationStatus[] = ['SUBMITTED', 'SHORTLISTED', 'INTERVIEWING']

const dayTime = (iso: string) =>
  new Intl.DateTimeFormat('en-IN', { weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' }).format(new Date(iso))

export function interviewEnd(interview: { scheduledAt: string; durationMinutes: number }): number {
  return new Date(interview.scheduledAt).getTime() + interview.durationMinutes * 60_000
}

export function nextStepFor(
  application: { status: ApplicationStatus | string; jobTitle: string },
  samples: CandidateWorkSample[] = [],
  interviews: (ApplicationInterview | CandidateInterview)[] = [],
  now = Date.now(),
): NextStep {
  const status = application.status as ApplicationStatus
  if (status === 'WITHDRAWN') return { kind: 'closed', label: 'You withdrew this application' }
  if (status === 'REJECTED') return { kind: 'closed', label: 'Nothing more is needed from you' }
  if (status === 'SELECTED') return { kind: 'selected', label: 'The employer will contact you' }

  if (ACTIVE.includes(status)) {
    const started = samples.find(s => s.status === 'STARTED')
    if (started) {
      return { kind: 'work-sample-started', label: 'Finish your work sample', to: `/work-samples/${started.assignmentId}`, at: started.deadlineAt, sample: started }
    }
    const assigned = samples.find(s => s.status === 'ASSIGNED')
    if (assigned) {
      return {
        kind: 'work-sample',
        label: assigned.dueAt ? `Start work sample by ${formatDate(assigned.dueAt, { weekday: 'short', day: 'numeric', month: 'short' })}` : 'Start your work sample',
        to: `/work-samples/${assigned.assignmentId}`,
        at: assigned.dueAt,
        sample: assigned,
      }
    }
    const upcoming = interviews
      .filter(i => i.status === 'SCHEDULED' && interviewEnd(i) >= now)
      .sort((a, b) => a.scheduledAt.localeCompare(b.scheduledAt))[0]
    if (upcoming) return { kind: 'interview', label: `Interview ${dayTime(upcoming.scheduledAt)}`, to: '/interviews', at: upcoming.scheduledAt, interview: upcoming }
  }
  return { kind: 'waiting', label: status === 'SUBMITTED' ? 'In blind review with the employer' : 'With the employer' }
}

/** Joins the candidate-wide task lists to one application by job title. */
export function tasksForJob(jobTitle: string, samples: CandidateWorkSample[] | undefined, interviews: CandidateInterview[] | undefined) {
  return {
    samples: (samples ?? []).filter(s => s.jobTitle === jobTitle),
    interviews: (interviews ?? []).filter(i => i.jobTitle === jobTitle),
  }
}
