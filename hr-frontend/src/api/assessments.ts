import { api, downloadFile } from './client'
import type {
  AssignmentStatus,
  AssignWorkSampleRequest,
  Interview,
  InterviewDetail,
  InterviewKit,
  InterviewQuestion,
  ScheduleInterviewRequest,
  ScorecardInput,
  ScorecardRevision,
  ScoreRequest,
  UpdateInterviewRequest,
  WorkSample,
  WorkSampleAssignment,
  WorkSampleInput,
  WorkSampleStatus,
} from './types'

/** Work samples (plan Section 5.6). */
export const workSamplesApi = {
  list: (jobId: number, signal?: AbortSignal) => api.get<WorkSample[]>(`/hr/jobs/${jobId}/work-samples`, undefined, signal),
  generate: (jobId: number) => api.post<WorkSample>(`/hr/jobs/${jobId}/work-samples/generate`),
  /** A hand-written DRAFT (no AI call); same validation as an edit. */
  create: (jobId: number, body: WorkSampleInput) => api.post<WorkSample>(`/hr/jobs/${jobId}/work-samples`, body),
  /** A DRAFT copy titled "… (copy)". */
  duplicate: (jobId: number, id: number) => api.post<WorkSample>(`/hr/jobs/${jobId}/work-samples/${id}/duplicate`),
  /** DRAFT and never assigned only (400 otherwise: archive instead). Audited. */
  remove: (jobId: number, id: number) => api.delete<null>(`/hr/jobs/${jobId}/work-samples/${id}`),
  update: (jobId: number, id: number, body: WorkSampleInput) => api.put<WorkSample>(`/hr/jobs/${jobId}/work-samples/${id}`, body),
  setStatus: (jobId: number, id: number, status: WorkSampleStatus) =>
    api.patch<WorkSample>(`/hr/jobs/${jobId}/work-samples/${id}/status`, { status }),
  assign: (jobId: number, applicationId: number, body: AssignWorkSampleRequest) =>
    api.post<WorkSampleAssignment>(`/hr/jobs/${jobId}/applicants/${applicationId}/work-samples`, body),
  assignments: (jobId: number, status: AssignmentStatus | undefined, signal?: AbortSignal) =>
    api.get<WorkSampleAssignment[]>(`/hr/jobs/${jobId}/work-sample-assignments`, { status }, signal),
  score: (assignmentId: number, body: ScoreRequest) => api.post<WorkSampleAssignment>(`/hr/work-sample-assignments/${assignmentId}/score`, body),
  /** ADMIN, HR_MANAGER; reason 1..1000 characters. CANCELLED/EXPIRED → 400, RECRUITER → 403. */
  cancel: (assignmentId: number, reason: string) =>
    api.post<WorkSampleAssignment>(`/hr/work-sample-assignments/${assignmentId}/cancel`, { reason }),
  /** Raw bytes (not the JSON envelope), saved with the server's file name; audited. */
  downloadFile: (assignmentId: number, fileId: number, fallbackName: string) =>
    downloadFile(`/hr/work-sample-assignments/${assignmentId}/files/${fileId}`, fallbackName),
}

/** Interview kits, interviews and scorecards (plan Section 5.6). */
export const interviewsApi = {
  kit: (jobId: number, signal?: AbortSignal) => api.get<InterviewKit>(`/hr/jobs/${jobId}/interview-kit`, undefined, signal),
  generateKit: (jobId: number) => api.post<InterviewKit>(`/hr/jobs/${jobId}/interview-kit/generate`),
  saveKit: (jobId: number, questions: InterviewQuestion[]) => api.put<InterviewKit>(`/hr/jobs/${jobId}/interview-kit`, { questions }),
  schedule: (jobId: number, applicationId: number, body: ScheduleInterviewRequest) =>
    api.post<Interview>(`/hr/jobs/${jobId}/applicants/${applicationId}/interviews`, body),
  forApplicant: (jobId: number, applicationId: number, signal?: AbortSignal) =>
    api.get<Interview[]>(`/hr/jobs/${jobId}/applicants/${applicationId}/interviews`, undefined, signal),
  update: (id: number, body: UpdateInterviewRequest) => api.patch<Interview>(`/interviews/${id}`, body),
  mine: (signal?: AbortSignal) => api.get<Interview[]>('/interviews/mine', undefined, signal),
  detail: (id: number, signal?: AbortSignal) => api.get<InterviewDetail>(`/interviews/${id}`, undefined, signal),
  submitScorecard: (id: number, body: ScorecardInput) => api.post<Interview>(`/interviews/${id}/scorecard`, body),
  /** Author only, until the application is decided: 403 other users, 400 decided/cancelled, 404 no scorecard yet. */
  updateScorecard: (id: number, body: ScorecardInput) => api.put<Interview>(`/interviews/${id}/scorecard`, body),
  /** Earlier versions of an edited scorecard, newest first (author, ADMIN, HR_MANAGER). */
  scorecardRevisions: (id: number, signal?: AbortSignal) => api.get<ScorecardRevision[]>(`/interviews/${id}/scorecard/revisions`, undefined, signal),
}
