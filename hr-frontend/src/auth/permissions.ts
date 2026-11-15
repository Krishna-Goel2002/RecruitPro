import type { Role } from '../api/types'

/**
 * What each role may do in this UI. The server stays the authority; this only hides actions a role cannot perform.
 * ADMIN: everything. HR_MANAGER: jobs + interviews. RECRUITER: jobs (read, comment, verify claims, score work
 * samples) + interviews. INTERVIEWER: interviews only.
 */
export type Capability =
  | 'jobs.view'
  | 'jobs.manage'
  | 'applicants.decide'
  | 'applicants.reveal'
  | 'applicants.comment'
  | 'applicants.verifyClaims'
  | 'applicants.downloadResume'
  | 'applicants.retryAnalysis'
  | 'applicants.rematch'
  | 'workSamples.manage'
  | 'workSamples.score'
  | 'workSamples.cancel'
  | 'workSamples.downloadFiles'
  | 'interviewKit.manage'
  | 'interviews.schedule'
  | 'interviews.view'
  | 'fairness.job'
  | 'audit.jobExport'
  | 'verification.view'
  | 'verification.review'
  | 'verification.queue'
  | 'verification.settings'
  | 'admin'
  | 'account'

const MANAGERS: Role[] = ['ADMIN', 'HR_MANAGER']
const READERS: Role[] = ['ADMIN', 'HR_MANAGER', 'RECRUITER']
const STAFF: Role[] = ['ADMIN', 'HR_MANAGER', 'RECRUITER', 'INTERVIEWER']

const matrix: Record<Capability, Role[]> = {
  'jobs.view': READERS,
  'jobs.manage': MANAGERS,
  'applicants.decide': MANAGERS,
  'applicants.reveal': MANAGERS,
  'applicants.comment': READERS,
  'applicants.verifyClaims': READERS,
  'applicants.downloadResume': READERS,
  'applicants.retryAnalysis': MANAGERS,
  'applicants.rematch': MANAGERS,
  'workSamples.manage': MANAGERS,
  'workSamples.score': READERS,
  // WP-PLAT: cancel is ADMIN/HR_MANAGER (RECRUITER 403); file download ADMIN/HR_MANAGER/RECRUITER.
  'workSamples.cancel': MANAGERS,
  'workSamples.downloadFiles': READERS,
  'interviewKit.manage': MANAGERS,
  'interviews.schedule': MANAGERS,
  'interviews.view': STAFF,
  'fairness.job': MANAGERS,
  'audit.jobExport': MANAGERS,
  // WP-VBE: the applicant Verification tab and Confirm/Overrule are ADMIN, HR_MANAGER, RECRUITER (INTERVIEWER 403).
  'verification.view': READERS,
  'verification.review': READERS,
  // Shadow queue, labels and metrics: ADMIN and HR_MANAGER. Mode and connector settings: ADMIN only.
  'verification.queue': MANAGERS,
  'verification.settings': ['ADMIN'],
  admin: ['ADMIN'],
  account: STAFF,
}

export function can(role: Role | null | undefined, capability: Capability): boolean {
  return role != null && matrix[capability].includes(role)
}

export function isStaff(role: Role | null | undefined): boolean {
  return role != null && STAFF.includes(role)
}

/** Where a role lands after sign-in. */
export function homePath(role: Role): string {
  if (role === 'ADMIN') return '/admin'
  if (role === 'INTERVIEWER') return '/interviews'
  return '/jobs'
}
