import type { AssistantContext } from '../api/assistantTypes'
import type { Capability } from '../auth/permissions'

/** The page context sent with every message, derived from the route (ASSISTANT_PLAN §3.2). */
export function pageContext(pathname: string): AssistantContext {
  const applicant = /^\/jobs\/(\d+)\/applicants\/(\d+)\/?$/.exec(pathname)
  if (applicant) return { page: 'applicant', jobId: Number(applicant[1]), applicationId: Number(applicant[2]) }
  const job = /^\/jobs\/(\d+)(\/edit)?\/?$/.exec(pathname)
  if (job) return { page: 'job', jobId: Number(job[1]) }
  if (pathname === '/jobs' || pathname === '/jobs/new') return { page: 'jobs' }
  if (pathname === '/admin' || pathname.startsWith('/admin/') || pathname === '/welcome') return { page: 'admin' }
  return { page: 'other' }
}

export interface Suggestion {
  label: string
  /** Text sent when the chip is pressed (defaults to the label). */
  prompt?: string
}

/** Suggested prompts for the current page and role. Interviewers only get interview-related ones (plan §1). */
export function suggestionsFor(context: AssistantContext, can: (c: Capability) => boolean): Suggestion[] {
  if (!can('jobs.view')) {
    return [
      { label: 'What needs my attention?' },
      { label: 'My interviews this week' },
      { label: 'Which scorecards are waiting for me?' },
      { label: 'How do I write a good scorecard?' },
    ]
  }
  const manage = can('jobs.manage')
  switch (context.page) {
    case 'applicant':
      return [
        { label: 'Tell me about this applicant' },
        { label: 'What is still unverified?' },
        { label: 'What should I ask in the interview?' },
        { label: 'Compare with the top-ranked applicant' },
      ]
    case 'job':
      return [
        { label: "Summarise this job's applicants" },
        { label: 'Who should I review first?' },
        { label: 'Which requirements are missing most often?' },
        ...(can('fairness.job') ? [{ label: 'Open the fairness report for this job' }] : []),
      ]
    case 'jobs':
      return [
        { label: 'What needs my attention?' },
        ...(manage ? [{ label: 'Post a new job' }] : []),
        { label: 'How are my published jobs doing?' },
        { label: 'How does verified coverage work?' },
      ]
    case 'admin':
      return [
        { label: 'What needs my attention?' },
        { label: 'How are my published jobs doing?' },
        ...(manage ? [{ label: 'Post a new job' }] : []),
        { label: 'How does verification work?' },
      ]
    default:
      return [
        { label: 'What needs my attention?' },
        ...(manage ? [{ label: 'Post a new job' }] : []),
        { label: 'My interviews this week' },
        { label: 'How does verified coverage work?' },
      ]
  }
}
