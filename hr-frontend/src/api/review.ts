import { api, downloadFile } from './client'
import type {
  ApplicantDetail,
  ApplicantListParams,
  ApplicantSummary,
  CandidateClaim,
  ClaimVerificationRequest,
  DecisionEvent,
  DecisionRequest,
  FairnessRange,
  FairnessReport,
  Interviewer,
  Page,
} from './types'

const base = (jobId: number) => `/hr/jobs/${jobId}`
const applicant = (jobId: number, applicationId: number) => `${base(jobId)}/applicants/${applicationId}`

/** HR applicant review (plan Section 5.4). */
export const reviewApi = {
  list: (jobId: number, params: ApplicantListParams, signal?: AbortSignal) =>
    api.get<Page<ApplicantSummary>>(`${base(jobId)}/applicants`, params, signal),
  detail: (jobId: number, applicationId: number, signal?: AbortSignal) =>
    api.get<ApplicantDetail>(applicant(jobId, applicationId), undefined, signal),
  decide: (jobId: number, applicationId: number, body: DecisionRequest) =>
    api.post<DecisionEvent>(`${applicant(jobId, applicationId)}/decisions`, body),
  comment: (jobId: number, applicationId: number, comment: string) =>
    api.put<DecisionEvent>(`${applicant(jobId, applicationId)}/review-comment`, { comment }),
  reveal: (jobId: number, applicationId: number) => api.post<ApplicantDetail>(`${applicant(jobId, applicationId)}/reveal`),
  downloadResume: (jobId: number, applicationId: number, fallbackName: string) =>
    downloadFile(`${applicant(jobId, applicationId)}/resume`, fallbackName),
  retryAnalysis: (jobId: number, applicationId: number) => api.patch<null>(`${applicant(jobId, applicationId)}/analysis/retry`),
  verifyClaim: (jobId: number, applicationId: number, claimId: number, body: ClaimVerificationRequest) =>
    api.post<CandidateClaim>(`${applicant(jobId, applicationId)}/claims/${claimId}/verification`, body),
  rematch: (jobId: number) => api.post<{ enqueued: number }>(`${base(jobId)}/rematch`),
  exportAudit: (jobId: number) => downloadFile(`${base(jobId)}/audit-export`, `job-${jobId}-audit.csv`),
  fairness: (jobId: number, range: FairnessRange, signal?: AbortSignal) =>
    api.get<FairnessReport>(`${base(jobId)}/fairness`, { from: range.from, to: range.to }, signal),
  interviewers: (signal?: AbortSignal) => api.get<Interviewer[]>('/hr/interviewers', undefined, signal),
}
