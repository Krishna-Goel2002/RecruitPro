import { api } from './client'
import type {
  AppNotification, AuthResponse, AuthUser, ChangePasswordRequest, MfaConfirmResult, MfaDisableRequest, MfaEnrollment, MfaStatus, Page, RecoveryCodes, UnreadCount,
} from './types'

/** The signed-in staff member's own account (plan Section 5.2): in-app inbox and password change. */
export const accountApi = {
  notifications: (page: number, size: number, signal?: AbortSignal) =>
    api.get<Page<AppNotification>>('/me/notifications', { page, size }, signal),
  unreadCount: (signal?: AbortSignal) => api.get<UnreadCount>('/me/notifications/unread-count', undefined, signal),
  markRead: (id: number) => api.patch<null>(`/me/notifications/${id}/read`),
  markAllRead: () => api.post<null>('/me/notifications/read-all'),
  /**
   * WP-BE-A deviation 1: returns the new session (AuthResponse + a new refresh cookie). Access tokens issued before
   * the change are rejected, so the caller must adopt the returned token.
   */
  changePassword: (body: ChangePasswordRequest) => api.post<AuthResponse>('/me/password', body),
  /** PATCH /me: own first and last name (1..100 characters each); audited. Returns the updated AuthUser. */
  updateName: (firstName: string, lastName: string) => api.patch<AuthUser>('/me', { firstName, lastName }),

  /** Staff MFA (WP-PLAT). 503 `MFA_NOT_CONFIGURED` without an encryption key; candidates get 403. */
  mfaStatus: (signal?: AbortSignal) => api.get<MfaStatus>('/me/mfa', undefined, signal),
  /** Replaces an unconfirmed enrolment; 409 when MFA is already on. */
  mfaEnroll: () => api.post<MfaEnrollment>('/me/mfa/enroll', {}),
  /** With a bearer token `session` is absent. Wrong code: 400. */
  mfaConfirm: (code: string) => api.post<MfaConfirmResult>('/me/mfa/confirm', { code }),
  /** `code` is a TOTP code or a recovery code. 400 for a wrong password/code or while the organisation requires MFA. */
  mfaDisable: (body: MfaDisableRequest) => api.post<null>('/me/mfa/disable', body),
  /**
   * Step-up (R1-U1): a current TOTP `code` (or the `password`). Wrong code/password: 400, counted towards the lockout;
   * while locked: 429 with Retry-After. Earlier codes stop working.
   */
  regenerateRecoveryCodes: (body: { code?: string; password?: string }) => api.post<RecoveryCodes>('/me/mfa/recovery-codes', body),
}
