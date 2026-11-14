import { apiRequest } from './client'
import type {
  AcceptInviteRequest,
  AuthResponse,
  AuthUser,
  InvitationPreview,
  LoginRequest,
  OrganizationSignupRequest,
  ResetPasswordRequest,
} from './types'

/** Public and account endpoints (plan Section 5.2). Public calls skip the bearer token and the refresh-retry. */
export const authApi = {
  login: (body: LoginRequest) => apiRequest<AuthResponse>('/auth/login', { method: 'POST', body, auth: false }),
  /** Revokes the refresh-token family and clears the cookie. Sends the bearer token when there is one. */
  logout: () => apiRequest<null>('/auth/logout', { method: 'POST', auth: false }),
  signup: (body: OrganizationSignupRequest) => apiRequest<AuthResponse>('/organizations/signup', { method: 'POST', body, auth: false }),
  previewInvitation: (token: string) => apiRequest<InvitationPreview>(`/auth/invitations/${encodeURIComponent(token)}`, { auth: false }),
  acceptInvite: (body: AcceptInviteRequest) => apiRequest<AuthResponse>('/auth/accept-invite', { method: 'POST', body, auth: false }),
  forgotPassword: (email: string) => apiRequest<null>('/auth/forgot-password', { method: 'POST', body: { email }, auth: false }),
  resetPassword: (body: ResetPasswordRequest) => apiRequest<null>('/auth/reset-password', { method: 'POST', body, auth: false }),
  me: () => apiRequest<AuthUser>('/me'),
}
