import { API_BASE_URL, apiRequest } from './client'
import type {
  AcceptInviteRequest,
  AuthResponse,
  AuthUser,
  InvitationPreview,
  LoginRequest,
  LoginResult,
  MfaConfirmResult,
  MfaEnrollment,
  MfaVerifyRequest,
  OrganizationSignupRequest,
  ResetPasswordRequest,
  SsoProvider,
} from './types'

/** Public and account endpoints (plan Section 5.2). Public calls skip the bearer token and the refresh-retry. */
export const authApi = {
  /** AuthResponse, or an MfaChallenge for staff with MFA (check with `isMfaChallenge`). */
  login: (body: LoginRequest) => apiRequest<LoginResult>('/auth/login', { method: 'POST', body, auth: false }),
  /** Second sign-in step: `{mfaToken, code}` or `{mfaToken, recoveryCode}`; sets the staff refresh cookie. */
  verifyMfa: (body: MfaVerifyRequest) => apiRequest<AuthResponse>('/auth/mfa/verify', { method: 'POST', body, auth: false }),
  /** Forced first enrolment during sign-in: authorised by the enrolment `mfaToken` in the body, not a bearer token. */
  enrollWithToken: (mfaToken: string) => apiRequest<MfaEnrollment>('/me/mfa/enroll', { method: 'POST', body: { mfaToken }, auth: false }),
  /** Returns the recovery codes and `session` (adopt it like a login response); sets the refresh cookie. */
  confirmWithToken: (mfaToken: string, code: string) =>
    apiRequest<MfaConfirmResult>('/me/mfa/confirm', { method: 'POST', body: { mfaToken, code }, auth: false }),
  ssoProviders: (signal?: AbortSignal) => apiRequest<SsoProvider[]>('/auth/sso/providers', { auth: false, signal }),
  /** Revokes the refresh-token family and clears the cookie. Sends the bearer token when there is one. */
  logout: () => apiRequest<null>('/auth/logout', { method: 'POST', auth: false }),
  signup: (body: OrganizationSignupRequest) => apiRequest<AuthResponse>('/organizations/signup', { method: 'POST', body, auth: false }),
  previewInvitation: (token: string) => apiRequest<InvitationPreview>(`/auth/invitations/${encodeURIComponent(token)}`, { auth: false }),
  acceptInvite: (body: AcceptInviteRequest) => apiRequest<AuthResponse>('/auth/accept-invite', { method: 'POST', body, auth: false }),
  forgotPassword: (email: string) => apiRequest<null>('/auth/forgot-password', { method: 'POST', body: { email }, auth: false }),
  resetPassword: (body: ResetPasswordRequest) => apiRequest<null>('/auth/reset-password', { method: 'POST', body, auth: false }),
  /** Confirms the e-mail address with the token from the verification e-mail (single use, 24 h). */
  verifyEmail: (token: string) => apiRequest<AuthUser>('/auth/verify-email', { method: 'POST', body: { token }, auth: false }),
  /** Sends a new verification e-mail to the signed-in user. */
  resendVerification: () => apiRequest<null>('/auth/resend-verification', { method: 'POST' }),
  me: () => apiRequest<AuthUser>('/me'),
}

/** SSO is a top-level browser navigation (the callback sets the refresh cookie and redirects to /sso/complete). */
export function ssoStartUrl(providerId: string): string {
  return `${API_BASE_URL}/auth/sso/${encodeURIComponent(providerId)}/start`
}
