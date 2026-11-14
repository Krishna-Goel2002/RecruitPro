import type { AdminJobParams, AdminUserParams, ApplicantListParams, AssignmentStatus, AuditParams, JobListParams } from './types'

/**
 * Query keys carry every parameter of the request, so a fast filter or page change can never render a response
 * that belongs to an older key (L7). Prefix keys are used for invalidation.
 */
export const qk = {
  jobs: ['jobs'] as const,
  jobList: (params: JobListParams) => ['jobs', 'list', params] as const,
  jobStats: (status: string) => ['jobs', 'stats', status] as const,
  job: (jobId: number) => ['jobs', 'detail', jobId] as const,

  applicants: (jobId: number) => ['applicants', jobId] as const,
  applicantList: (jobId: number, params: ApplicantListParams) => ['applicants', jobId, 'list', params] as const,
  applicant: (jobId: number, applicationId: number) => ['applicants', jobId, 'detail', applicationId] as const,

  workSamples: (jobId: number) => ['workSamples', jobId] as const,
  assignments: (jobId: number, status: AssignmentStatus | 'ALL') => ['workSamples', jobId, 'assignments', status] as const,
  interviewKit: (jobId: number) => ['interviewKit', jobId] as const,
  jobFairness: (jobId: number) => ['fairness', 'job', jobId] as const,
  interviewers: ['interviewers'] as const,

  interviews: ['interviews'] as const,
  myInterviews: ['interviews', 'mine'] as const,
  interview: (id: number) => ['interviews', 'detail', id] as const,

  adminDashboard: ['admin', 'dashboard'] as const,
  adminUsers: (params: AdminUserParams) => ['admin', 'users', params] as const,
  adminInvitations: ['admin', 'invitations'] as const,
  adminOrganization: ['admin', 'organization'] as const,
  adminJobs: (params: AdminJobParams) => ['admin', 'jobs', params] as const,
  adminAudit: (params: AuditParams) => ['admin', 'audit', params] as const,
  adminFairness: ['fairness', 'organization'] as const,

  notifications: ['notifications'] as const,
  notificationPage: (page: number) => ['notifications', 'page', page] as const,
  unreadCount: ['notifications', 'unread'] as const,
}
