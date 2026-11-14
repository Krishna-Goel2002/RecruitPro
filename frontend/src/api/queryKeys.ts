import type { JobSearchParams } from './types'

/** React Query keys. Every key starts with the resource so invalidation can target a whole group. */
export const queryKeys = {
  jobs: (params: JobSearchParams) => ['jobs', params] as const,
  job: (id: number) => ['job', id] as const,
  profile: ['profile'] as const,
  resumes: ['resumes'] as const,
  resumeAnalysis: (id: number) => ['resume-analysis', id] as const,
  claims: ['claims'] as const,
  applications: (page: number) => ['applications', page] as const,
  applicationsAll: ['applications'] as const,
  application: (id: number) => ['application', id] as const,
  workSamples: ['work-samples'] as const,
  interviews: ['interviews'] as const,
  notifications: (page: number) => ['notifications', page] as const,
  notificationsAll: ['notifications'] as const,
  unreadCount: ['unread-count'] as const,
  survey: ['equal-opportunity'] as const,
}
