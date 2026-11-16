import { lazy, Suspense, type ComponentType } from 'react'

/**
 * Route-level code splitting for part-A screens that are not on the first paint (DESIGN_SYSTEM §13 budget): the job
 * wizard, first run, and the secondary account-entry pages. Sign-in stays in the main bundle.
 */
function page(load: () => Promise<ComponentType>) {
  const Component = lazy(() => load().then(C => ({ default: C })))
  return function LazyPage() {
    return (
      <Suspense fallback={null}>
        <Component />
      </Suspense>
    )
  }
}

export const JobFormPage = page(() => import('./jobs/JobFormPage').then(m => m.JobFormPage))
export const WelcomePage = page(() => import('./home/WelcomePage').then(m => m.WelcomePage))
export const SignupPage = page(() => import('./auth/SignupPage').then(m => m.SignupPage))
export const AcceptInvitePage = page(() => import('./auth/AcceptInvitePage').then(m => m.AcceptInvitePage))
export const ForgotPasswordPage = page(() => import('./auth/PasswordPages').then(m => m.ForgotPasswordPage))
export const ResetPasswordPage = page(() => import('./auth/PasswordPages').then(m => m.ResetPasswordPage))
export const VerifyEmailPage = page(() => import('./auth/VerifyEmailPage').then(m => m.VerifyEmailPage))
export const SsoCompletePage = page(() => import('./auth/SsoCompletePage').then(m => m.SsoCompletePage))

// Admin and account settings: not on the first paint for most staff.
export const AdminAuditPage = page(() => import('./admin/AdminAuditPage').then(m => m.AdminAuditPage))
export const AdminDashboardPage = page(() => import('./admin/AdminDashboardPage').then(m => m.AdminDashboardPage))
export const AdminFairnessPage = page(() => import('./admin/AdminFairnessPage').then(m => m.AdminFairnessPage))
export const AdminInvitationsPage = page(() => import('./admin/AdminInvitationsPage').then(m => m.AdminInvitationsPage))
export const AdminJobsPage = page(() => import('./admin/AdminJobsPage').then(m => m.AdminJobsPage))
export const AdminOrganizationPage = page(() => import('./admin/AdminOrganizationPage').then(m => m.AdminOrganizationPage))
export const AdminUsersPage = page(() => import('./admin/AdminUsersPage').then(m => m.AdminUsersPage))
export const AdminVerificationPage = page(() => import('./admin/AdminVerificationPage').then(m => m.AdminVerificationPage))
export const SecurityPage = page(() => import('./account/SecurityPage').then(m => m.SecurityPage))
export const NamePage = page(() => import('./account/NamePage').then(m => m.NamePage))
export const ChangePasswordPage = page(() => import('./account/ChangePasswordPage').then(m => m.ChangePasswordPage))

// Assistant full page (the side panel itself is in the main bundle).
export const AssistantPage = page(() => import('../assistant/AssistantPage').then(m => m.AssistantPage))
