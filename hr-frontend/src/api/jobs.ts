import { api } from './client'
import type { CompileRequest, CompileResult, JobInput, JobListParams, JobStaff, JobStatus, Page, Requirement, RequirementInput } from './types'

/** Jobs and requirements (plan Section 5.3). */
export const jobsApi = {
  list: (params: JobListParams, signal?: AbortSignal) => api.get<Page<JobStaff>>('/jobs', params, signal),
  get: (id: number, signal?: AbortSignal) => api.get<JobStaff>(`/jobs/${id}`, undefined, signal),
  create: (body: JobInput) => api.post<JobStaff>('/jobs', body),
  update: (id: number, body: JobInput) => api.put<JobStaff>(`/jobs/${id}`, body),
  setStatus: (id: number, status: JobStatus) => api.patch<JobStaff>(`/jobs/${id}/status`, { status }),
  /** DRAFT jobs without applications only (published jobs are closed instead). Audited. */
  remove: (id: number) => api.delete<null>(`/jobs/${id}`),
  /** A new DRAFT with the job's fields and requirements, titled "… (copy)". */
  duplicate: (id: number) => api.post<JobStaff>(`/jobs/${id}/duplicate`),
  /** Replace-all; increments requirementsVersion and re-matches when the job is PUBLISHED/ON_HOLD. */
  replaceRequirements: (id: number, requirements: RequirementInput[]) => api.put<JobStaff>(`/jobs/${id}/requirements`, { requirements }),
  addRequirement: (id: number, body: RequirementInput) => api.post<Requirement>(`/jobs/${id}/requirements`, body),
  compileRequirements: (body: CompileRequest) => api.post<CompileResult>('/jobs/compile-requirements', body),
}
