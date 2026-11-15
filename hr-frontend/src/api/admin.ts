import { api, downloadFile } from './client'
import type {
  AdminDashboard,
  AdminJob,
  AdminJobParams,
  AdminUser,
  AdminUserParams,
  AuditEvent,
  AuditParams,
  FairnessRange,
  FairnessReport,
  Invitation,
  JobStatus,
  Organization,
  OrganizationUpdate,
  Page,
  StaffRole,
} from './types'

/** Administration (plan Section 5.7; WP-BE-A endpoints that already exist). */
export const adminApi = {
  dashboard: (signal?: AbortSignal) => api.get<AdminDashboard>('/admin/dashboard', undefined, signal),
  users: (params: AdminUserParams, signal?: AbortSignal) => api.get<Page<AdminUser>>('/admin/users', params, signal),
  setUserEnabled: (id: number, enabled: boolean) => api.patch<AdminUser>(`/admin/users/${id}/status`, { enabled }),
  setUserRole: (id: number, role: StaffRole) => api.patch<AdminUser>(`/admin/users/${id}/role`, { role }),
  invitations: (signal?: AbortSignal) => api.get<Invitation[]>('/admin/invitations', undefined, signal),
  invite: (email: string, role: StaffRole) => api.post<Invitation>('/admin/invitations', { email, role }),
  revokeInvitation: (id: number) => api.delete<null>(`/admin/invitations/${id}`),
  organization: (signal?: AbortSignal) => api.get<Organization>('/admin/organization', undefined, signal),
  updateOrganization: (body: OrganizationUpdate) => api.patch<Organization>('/admin/organization', body),
  jobs: (params: AdminJobParams, signal?: AbortSignal) => api.get<Page<AdminJob>>('/admin/jobs', params, signal),
  overrideJobStatus: (id: number, status: JobStatus, reason: string) => api.patch<AdminJob>(`/admin/jobs/${id}/status`, { status, reason }),
  audit: (params: AuditParams, signal?: AbortSignal) => api.get<Page<AuditEvent>>('/admin/audit-logs', params, signal),
  exportAudit: (from: string | undefined, to: string | undefined) => downloadFile('/admin/audit-export', 'audit.csv', { from, to }),
  /** `from`/`to`: inclusive ISO dates (UTC days); `from > to` or a malformed date is 400. */
  fairness: (range: FairnessRange, signal?: AbortSignal) => api.get<FairnessReport>('/admin/fairness', { from: range.from, to: range.to }, signal),
}
