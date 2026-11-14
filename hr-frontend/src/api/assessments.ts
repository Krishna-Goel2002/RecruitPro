import { api } from './client'
import type {
  AssignmentStatus,
  AssignWorkSampleRequest,
  Interview,
  InterviewDetail,
  InterviewKit,
  InterviewQuestion,
  ScheduleInterviewRequest,
  ScorecardInput,
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
  update: (jobId: number, id: number, body: WorkSampleInput) => api.put<WorkSample>(`/hr/jobs/${jobId}/work-samples/${id}`, body),
  setStatus: (jobId: number, id: number, status: WorkSampleStatus) =>
    api.patch<WorkSample>(`/hr/jobs/${jobId}/work-samples/${id}/status`, { status }),
  assign: (jobId: number, applicationId: number, body: AssignWorkSampleRequest) =>
    api.post<WorkSampleAssignment>(`/hr/jobs/${jobId}/applicants/${applicationId}/work-samples`, body),
  assignments: (jobId: number, status: AssignmentStatus | undefined, signal?: AbortSignal) =>
    api.get<WorkSampleAssignment[]>(`/hr/jobs/${jobId}/work-sample-assignments`, { status }, signal),
  score: (assignmentId: number, body: ScoreRequest) => api.post<WorkSampleAssignment>(`/hr/work-sample-assignments/${assignmentId}/score`, body),
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
}
