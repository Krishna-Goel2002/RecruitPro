import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { HomeRedirect, PublicOnly, RequireAuth } from './auth/guards'
import { AppLayout } from './components/Layout'
import { ApplicantDetailPage } from './pages/applicants/ApplicantDetailPage'
import { NotificationsPage } from './pages/account/NotificationsPage'
import { LoginPage } from './pages/auth/LoginPage'
import { InterviewDetailPage } from './pages/interviews/InterviewDetailPage'
import { InterviewsPage } from './pages/interviews/InterviewsPage'
import { JobDetailPage } from './pages/jobs/JobDetailPage'
import { JobsListPage } from './pages/jobs/JobsListPage'
import { NotFoundPage } from './pages/StatusPages'
// Part A: code-split routes (wizard, first run, secondary account-entry pages).
import { AcceptInvitePage, ForgotPasswordPage, JobFormPage, ResetPasswordPage, SignupPage, SsoCompletePage, VerifyEmailPage, WelcomePage } from './pages/lazyRoutes'
// Admin and account-settings screens are code-split too (bundle budget).
import { AdminAuditPage, AdminDashboardPage, AdminFairnessPage, AdminInvitationsPage, AdminJobsPage, AdminOrganizationPage, AdminUsersPage, AdminVerificationPage, ChangePasswordPage, SecurityPage } from './pages/lazyRoutes'

// Living style guide (hidden route, not linked from navigation). Lazy, so it never weighs on the app bundle.
const DesignGallery = lazy(() => import('./design/gallery/DesignGallery'))

/**
 * Routes and role guards. ADMIN: everything. HR_MANAGER: jobs + interviews. RECRUITER: jobs (read, comment, verify
 * claims, score work samples) + interviews. INTERVIEWER: interviews only. Every staff role: notifications inbox and
 * change password. The server stays the authority.
 */
export default function App() {
  return (
    <Routes>
      <Route path="/__design" element={<Suspense fallback={null}><DesignGallery portal="staff" /></Suspense>} />
      <Route path="/login" element={<PublicOnly><LoginPage /></PublicOnly>} />
      <Route path="/signup" element={<PublicOnly><SignupPage /></PublicOnly>} />
      <Route path="/forgot-password" element={<PublicOnly><ForgotPasswordPage /></PublicOnly>} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/accept-invite" element={<AcceptInvitePage />} />
      <Route path="/sso/complete" element={<SsoCompletePage />} />
      <Route path="/verify-email" element={<VerifyEmailPage />} />

      <Route element={<RequireAuth><AppLayout /></RequireAuth>}>
        <Route index element={<HomeRedirect />} />

        <Route path="welcome" element={<RequireAuth capability="admin"><WelcomePage /></RequireAuth>} />
        <Route path="jobs" element={<RequireAuth capability="jobs.view"><JobsListPage /></RequireAuth>} />
        <Route path="jobs/new" element={<RequireAuth capability="jobs.manage"><JobFormPage /></RequireAuth>} />
        <Route path="jobs/:jobId" element={<RequireAuth capability="jobs.view"><JobDetailPage /></RequireAuth>} />
        <Route path="jobs/:jobId/edit" element={<RequireAuth capability="jobs.manage"><JobFormPage /></RequireAuth>} />
        <Route path="jobs/:jobId/applicants/:applicationId" element={<RequireAuth capability="jobs.view"><ApplicantDetailPage /></RequireAuth>} />

        <Route path="interviews" element={<RequireAuth capability="interviews.view"><InterviewsPage /></RequireAuth>} />
        <Route path="interviews/:interviewId" element={<RequireAuth capability="interviews.view"><InterviewDetailPage /></RequireAuth>} />

        <Route path="notifications" element={<RequireAuth capability="account"><NotificationsPage /></RequireAuth>} />
        <Route path="account/password" element={<RequireAuth capability="account"><ChangePasswordPage /></RequireAuth>} />
        <Route path="account/security" element={<RequireAuth capability="account"><SecurityPage /></RequireAuth>} />

        <Route path="admin" element={<RequireAuth capability="admin"><AdminDashboardPage /></RequireAuth>} />
        <Route path="admin/users" element={<RequireAuth capability="admin"><AdminUsersPage /></RequireAuth>} />
        <Route path="admin/invitations" element={<RequireAuth capability="admin"><AdminInvitationsPage /></RequireAuth>} />
        <Route path="admin/organization" element={<RequireAuth capability="admin"><AdminOrganizationPage /></RequireAuth>} />
        <Route path="admin/organisation" element={<Navigate to="/admin/organization" replace />} />
        <Route path="admin/jobs" element={<RequireAuth capability="admin"><AdminJobsPage /></RequireAuth>} />
        <Route path="admin/audit" element={<RequireAuth capability="admin"><AdminAuditPage /></RequireAuth>} />
        <Route path="admin/fairness" element={<RequireAuth capability="admin"><AdminFairnessPage /></RequireAuth>} />
        <Route path="admin/verification" element={<RequireAuth capability="verification.queue"><AdminVerificationPage /></RequireAuth>} />

        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
