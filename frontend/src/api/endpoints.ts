/**
 * One typed function per API call used by the candidate portal (plan Section 5).
 *
 * Several endpoints (5.4-5.7: claims, application detail, work samples, interviews, survey, export/delete) are
 * built in parallel with this package. The small `normalize*` helpers below fill defaults for fields a partial
 * backend may still omit, so pages can rely on the plan's types. Remove them once the backend matches the plan.
 */
import { ApiError, apiDownload, apiRequest, type DownloadedFile } from './client'
import type {
  Application,
  ApplicationDetail,
  ApplicationTimelineEntry,
  ApplyRequest,
  AppNotification,
  AuthResponse,
  AuthUser,
  CandidateClaim,
  CandidateInterview,
  CandidateProfile,
  CandidateProfileUpdate,
  CandidateWorkSample,
  ChangePasswordRequest,
  ClaimCreateRequest,
  DeleteAccountRequest,
  EqualOpportunityRequest,
  EqualOpportunityResponse,
  FactCorrectionRequest,
  JobPublic,
  JobSearchParams,
  LoginRequest,
  Page,
  RegisterRequest,
  Requirement,
  ResetPasswordRequest,
  Resume,
  ResumeAnalysis,
  ResumeFact,
  UnreadCount,
  WorkSampleSubmitRequest,
} from './types'

// ---------------------------------------------------------------------------------------------------------------------
// Normalisers (tolerate fields that the current backend does not send yet)
// ---------------------------------------------------------------------------------------------------------------------

function normalizeRequirement(requirement: Requirement): Requirement {
  return {
    ...requirement,
    knockout: requirement.knockout ?? requirement.mandatory,
    minimumYears: requirement.minimumYears ?? null,
    alternatives: requirement.alternatives ?? [],
    proficiency: requirement.proficiency ?? null,
  }
}

function normalizeJob(job: JobPublic): JobPublic {
  return {
    ...job,
    organizationName: job.organizationName ?? '',
    minimumSalary: job.minimumSalary ?? null,
    maximumSalary: job.maximumSalary ?? null,
    deadline: job.deadline ?? null,
    publishedAt: job.publishedAt ?? null,
    requirements: (job.requirements ?? []).map(normalizeRequirement),
  }
}

function normalizeApplication<T extends Application>(application: T): T {
  return {
    ...application,
    organizationName: application.organizationName ?? '',
    coverLetter: application.coverLetter ?? null,
    statusChangedAt: application.statusChangedAt ?? null,
  }
}

function normalizeApplicationDetail(detail: ApplicationDetail): ApplicationDetail {
  const base = normalizeApplication(detail)
  const derivedTimeline: ApplicationTimelineEntry[] = [{ status: 'SUBMITTED', at: base.appliedAt }]
  if (base.status !== 'SUBMITTED') {
    derivedTimeline.push({ status: base.status, at: base.statusChangedAt ?? base.updatedAt })
  }
  return {
    ...base,
    timeline: detail.timeline && detail.timeline.length > 0 ? detail.timeline : derivedTimeline,
    requirements: detail.requirements === undefined ? null : detail.requirements,
    workSamples: detail.workSamples ?? [],
    interviews: detail.interviews ?? [],
  }
}

function normalizeFact(fact: ResumeFact): ResumeFact {
  return {
    ...fact,
    extractedValue: fact.extractedValue ?? fact.value,
    evidence: fact.evidence ?? null,
    sourceSection: fact.sourceSection ?? null,
    tier: fact.tier ?? null,
    claimedValue: fact.claimedValue ?? null,
    claimId: fact.claimId ?? null,
  }
}

function normalizeAnalysis(analysis: ResumeAnalysis): ResumeAnalysis {
  return {
    ...analysis,
    parserVersion: analysis.parserVersion ?? null,
    errorCode: analysis.errorCode ?? null,
    analyzedAt: analysis.analyzedAt ?? null,
    facts: (analysis.facts ?? []).map(normalizeFact),
    flags: analysis.flags ?? [],
  }
}

function normalizeClaim(claim: CandidateClaim): CandidateClaim {
  return {
    ...claim,
    evidenceUrl: claim.evidenceUrl ?? null,
    verifiedAt: claim.verifiedAt ?? null,
    verificationNote: claim.verificationNote ?? null,
    verificationStatus: claim.verificationStatus ?? (claim.verifiedAt ? undefined : 'UNVERIFIED'),
  }
}

/** Unread notifications have no `readAt` on the wire (non_null serialisation). */
function normalizeNotification(notification: AppNotification): AppNotification {
  return { ...notification, readAt: notification.readAt ?? null }
}

function normalizePage<T>(page: Page<T>, map: (item: T) => T): Page<T> {
  return { ...page, content: page.content.map(map) }
}

// ---------------------------------------------------------------------------------------------------------------------
// Auth and account (5.2)
// ---------------------------------------------------------------------------------------------------------------------

export const authApi = {
  login: (body: LoginRequest) => apiRequest<AuthResponse>('/auth/login', { method: 'POST', body, auth: false }),
  register: (body: RegisterRequest) => apiRequest<AuthResponse>('/auth/register', { method: 'POST', body, auth: false }),
  logout: () => apiRequest<null>('/auth/logout', { method: 'POST', auth: false }),
  verifyEmail: (token: string) => apiRequest<AuthUser>('/auth/verify-email', { method: 'POST', body: { token }, auth: false }),
  resendVerification: () => apiRequest<null>('/auth/resend-verification', { method: 'POST' }),
  forgotPassword: (email: string) =>
    apiRequest<null>('/auth/forgot-password', { method: 'POST', body: { email }, auth: false }),
  resetPassword: (body: ResetPasswordRequest) =>
    apiRequest<null>('/auth/reset-password', { method: 'POST', body, auth: false }),
}

export const meApi = {
  get: () => apiRequest<AuthUser>('/me'),
  /** WP-BE-A deviation: returns the new session (other sessions are revoked). */
  changePassword: (body: ChangePasswordRequest) =>
    apiRequest<AuthResponse | null>('/me/password', { method: 'POST', body }),
  notifications: async (page: number, size: number) =>
    normalizePage(await apiRequest<Page<AppNotification>>('/me/notifications', { query: { page, size } }), normalizeNotification),
  unreadCount: () => apiRequest<UnreadCount>('/me/notifications/unread-count'),
  markRead: (id: number) => apiRequest<null>(`/me/notifications/${id}/read`, { method: 'PATCH' }),
  markAllRead: () => apiRequest<null>('/me/notifications/read-all', { method: 'POST' }),
}

// ---------------------------------------------------------------------------------------------------------------------
// Jobs (5.3, candidate view)
// ---------------------------------------------------------------------------------------------------------------------

export const jobsApi = {
  search: async (params: JobSearchParams) =>
    normalizePage(
      await apiRequest<Page<JobPublic>>('/jobs', {
        query: {
          page: params.page,
          size: params.size,
          search: params.search?.trim(),
          department: params.department?.trim(),
          location: params.location?.trim(),
          jobType: params.jobType,
        },
      }),
      normalizeJob,
    ),
  get: async (id: number) => normalizeJob(await apiRequest<JobPublic>(`/jobs/${id}`)),
  apply: async (jobId: number, body: ApplyRequest) =>
    normalizeApplication(await apiRequest<Application>(`/jobs/${jobId}/applications`, { method: 'POST', body })),
}

// ---------------------------------------------------------------------------------------------------------------------
// Candidate (5.5, 5.6)
// ---------------------------------------------------------------------------------------------------------------------

export const profileApi = {
  get: () => apiRequest<CandidateProfile>('/candidates/me'),
  update: (body: CandidateProfileUpdate) => apiRequest<CandidateProfile>('/candidates/me', { method: 'PUT', body }),
}

export const resumesApi = {
  list: () => apiRequest<Resume[]>('/candidates/me/resumes'),
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return apiRequest<Resume>('/candidates/me/resumes', { method: 'POST', body: form })
  },
  download: (id: number): Promise<DownloadedFile> => apiDownload(`/candidates/me/resumes/${id}/download`),
  analysis: async (id: number) =>
    normalizeAnalysis(await apiRequest<ResumeAnalysis>(`/candidates/me/resumes/${id}/analysis`)),
  retryAnalysis: (id: number) => apiRequest<null>(`/candidates/me/resumes/${id}/analysis/retry`, { method: 'PATCH' }),
  /** Plan 5.5: a changed value creates or updates a SELF_CLAIMED claim; the extracted value is kept. */
  suggestCorrection: async (resumeId: number, factId: number, body: FactCorrectionRequest) =>
    normalizeFact(
      await apiRequest<ResumeFact>(`/candidates/me/resumes/${resumeId}/analysis/facts/${factId}`, {
        method: 'PATCH',
        body,
      }),
    ),
}

export const claimsApi = {
  list: async () => (await apiRequest<CandidateClaim[]>('/candidates/me/claims')).map(normalizeClaim),
  create: async (body: ClaimCreateRequest) =>
    normalizeClaim(await apiRequest<CandidateClaim>('/candidates/me/claims', { method: 'POST', body })),
  remove: (id: number) => apiRequest<null>(`/candidates/me/claims/${id}`, { method: 'DELETE' }),
}

export const applicationsApi = {
  list: async (page: number, size: number) =>
    normalizePage(
      await apiRequest<Page<Application>>('/candidates/me/applications', { query: { page, size } }),
      normalizeApplication,
    ),
  get: async (id: number) =>
    normalizeApplicationDetail(await apiRequest<ApplicationDetail>(`/candidates/me/applications/${id}`)),
  withdraw: async (id: number) =>
    normalizeApplication(
      await apiRequest<Application>(`/candidates/me/applications/${id}/withdraw`, { method: 'PATCH' }),
    ),
}

export const workSamplesApi = {
  list: () => apiRequest<CandidateWorkSample[]>('/candidates/me/work-samples'),
  start: (assignmentId: number) =>
    apiRequest<CandidateWorkSample>(`/candidates/me/work-samples/${assignmentId}/start`, { method: 'POST' }),
  submit: (assignmentId: number, body: WorkSampleSubmitRequest) =>
    apiRequest<CandidateWorkSample>(`/candidates/me/work-samples/${assignmentId}/submit`, { method: 'POST', body }),
}

export const interviewsApi = {
  list: () => apiRequest<CandidateInterview[]>('/candidates/me/interviews'),
}

export const privacyApi = {
  /** Resolves `null` when no survey answers are stored (404 or empty data). */
  getSurvey: async (): Promise<EqualOpportunityResponse | null> => {
    try {
      return await apiRequest<EqualOpportunityResponse | null>('/candidates/me/equal-opportunity')
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null
      throw error
    }
  },
  saveSurvey: (body: EqualOpportunityRequest) =>
    apiRequest<EqualOpportunityResponse | null>('/candidates/me/equal-opportunity', { method: 'PUT', body }),
  deleteSurvey: () => apiRequest<null>('/candidates/me/equal-opportunity', { method: 'DELETE' }),
  exportData: (): Promise<DownloadedFile> => apiDownload('/candidates/me/export'),
  deleteAccount: (body: DeleteAccountRequest) => apiRequest<null>('/candidates/me', { method: 'DELETE', body }),
}
