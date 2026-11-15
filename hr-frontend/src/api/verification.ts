import { api } from './client'
import type {
  Page,
  ShadowQueueItem,
  StaffVerdict,
  StaffVerification,
  VerdictLabel,
  VerdictLabelRequest,
  VerdictReviewRequest,
  VerificationMetrics,
  VerificationSettings,
  VerificationSettingsUpdate,
} from './types'

/**
 * Claim verification for staff (WP-VBE, docs/plan/WP-VBE-NOTES.md 2.3).
 * - Applicant view and review: ADMIN, HR_MANAGER, RECRUITER (INTERVIEWER gets 403).
 * - Settings: ADMIN. Shadow queue, labels, metrics: ADMIN and HR_MANAGER.
 */
export const verificationApi = {
  forApplication: (applicationId: number, signal?: AbortSignal) =>
    api.get<StaffVerification>(`/hr/applications/${applicationId}/verification`, undefined, signal),
  /** OVERRULE needs a note (400 otherwise); VISIBLE mode only. The backend re-matches the candidate in this org. */
  review: (verdictId: number, body: VerdictReviewRequest) => api.post<StaffVerdict>(`/hr/verdicts/${verdictId}/review`, body),

  settings: (signal?: AbortSignal) => api.get<VerificationSettings>('/admin/verification/settings', undefined, signal),
  updateSettings: (body: VerificationSettingsUpdate) => api.put<VerificationSettings>('/admin/verification/settings', body),
  shadowQueue: (page: number, size: number, signal?: AbortSignal) =>
    api.get<Page<ShadowQueueItem>>('/admin/verification/shadow-queue', { page, size }, signal),
  label: (verdictId: number, body: VerdictLabelRequest) => api.post<VerdictLabel>(`/admin/verification/verdicts/${verdictId}/label`, body),
  metrics: (signal?: AbortSignal) => api.get<VerificationMetrics>('/admin/verification/metrics', undefined, signal),
}
