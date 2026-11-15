import { Navigate, Route, Routes } from 'react-router-dom'
import { HomeRedirect, PublicOnly, RequireAuth } from './auth/guards'
import { AppLayout } from './components/Layout'
import { AdminAuditPage } from './pages/admin/AdminAuditPage'
import { AdminDashboardPage } from './pages/admin/AdminDashboardPage'
import { AdminFairnessPage } from './pages/admin/AdminFairnessPage'
import { AdminInvitationsPage } from './pages/admin/AdminInvitationsPage'
import { AdminJobsPage } from './pages/admin/AdminJobsPage'
import { AdminOrganizationPage } from './pages/admin/AdminOrganizationPage'
import { AdminUsersPage } from './pages/admin/AdminUsersPage'
import { AdminVerificationPage } from './pages/admin/AdminVerificationPage'
import { ApplicantDetailPage } from './pages/applicants/ApplicantDetailPage'
import { ChangePasswordPage } from './pages/account/ChangePasswordPage'
import { NotificationsPage } from './pages/account/NotificationsPage'
import { SecurityPage } from './pages/account/SecurityPage'
import { AcceptInvitePage } from './pages/auth/AcceptInvitePage'
import { LoginPage } from './pages/auth/LoginPage'
import { ForgotPasswordPage, ResetPasswordPage } from './pages/auth/PasswordPages'
import { SignupPage } from './pages/auth/SignupPage'
import { SsoCompletePage } from './pages/auth/SsoCompletePage'
import { VerifyEmailPage } from './pages/auth/VerifyEmailPage'
import { InterviewDetailPage } from './pages/interviews/InterviewDetailPage'
import { InterviewsPage } from './pages/interviews/InterviewsPage'
import { JobDetailPage } from './pages/jobs/JobDetailPage'
import { JobFormPage } from './pages/jobs/JobFormPage'
import { JobsListPage } from './pages/jobs/JobsListPage'
import { NotFoundPage } from './pages/StatusPages'

/**
 * Routes and role guards. ADMIN: everything. HR_MANAGER: jobs + interviews. RECRUITER: jobs (read, comment, verify
 * claims, score work samples) + interviews. INTERVIEWER: interviews only. Every staff role: notifications inbox and
 * change password. The server stays the authority.
 */
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<PublicOnly><LoginPage /></PublicOnly>} />
      <Route path="/signup" element={<PublicOnly><SignupPage /></PublicOnly>} />
      <Route path="/forgot-password" element={<PublicOnly><ForgotPasswordPage /></PublicOnly>} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/accept-invite" element={<AcceptInvitePage />} />
      <Route path="/sso/complete" element={<SsoCompletePage />} />
      <Route path="/verify-email" element={<VerifyEmailPage />} />

      <Route element={<RequireAuth><AppLayout /></RequireAuth>}>
        <Route index element={<HomeRedirect />} />

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
