import { api } from './client'
import type { AppNotification, AuthResponse, ChangePasswordRequest, Page, UnreadCount } from './types'

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
}
