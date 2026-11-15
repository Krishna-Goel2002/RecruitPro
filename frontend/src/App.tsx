import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { ApiError } from './api/client'
import { AuthProvider } from './auth/AuthProvider'
import { PublicOnly, RequireAuth } from './auth/guards'
import { Layout } from './components/Layout'
import { NoticeProvider } from './components/Notices'
import { ApplicationDetailPage } from './pages/ApplicationDetailPage'
import { ApplicationsPage } from './pages/ApplicationsPage'
import { AuthLayout } from './pages/auth/AuthLayout'
import { ForgotPasswordPage } from './pages/auth/ForgotPasswordPage'
import { LoginPage } from './pages/auth/LoginPage'
import { RegisterPage } from './pages/auth/RegisterPage'
import { ResetPasswordPage } from './pages/auth/ResetPasswordPage'
import { VerifyEmailPage } from './pages/auth/VerifyEmailPage'
import { EvidencePage } from './pages/EvidencePage'
import { InterviewsPage } from './pages/InterviewsPage'
import { JobDetailPage } from './pages/JobDetailPage'
import { JobsPage } from './pages/JobsPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { NotificationsPage } from './pages/NotificationsPage'
import { PrivacyPage } from './pages/PrivacyPage'
import { ProfilePage } from './pages/ProfilePage'
import { RefereePage } from './pages/RefereePage'
import { ResumesPage } from './pages/ResumesPage'
import { VerificationPage } from './pages/VerificationPage'
import { WorkSampleResponsePage } from './pages/WorkSampleResponsePage'
import { WorkSamplesPage } from './pages/WorkSamplesPage'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Retry only transient failures (network, 5xx); 4xx answers are final.
      retry: (failureCount, error) =>
        failureCount < 2 && error instanceof ApiError && (error.isNetworkError || error.status >= 500),
      staleTime: 30_000,
      refetchOnWindowFocus: false,
    },
    mutations: { retry: false },
  },
})

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <NoticeProvider>
          <AuthProvider>
            <Routes>
              <Route element={<AuthLayout />}>
                <Route element={<PublicOnly />}>
                  <Route path="/login" element={<LoginPage />} />
                  <Route path="/register" element={<RegisterPage />} />
                  <Route path="/forgot-password" element={<ForgotPasswordPage />} />
                </Route>
                <Route path="/reset-password" element={<ResetPasswordPage />} />
                <Route path="/verify-email" element={<VerifyEmailPage />} />
              </Route>

              {/* The referee's page: no sign-in, outside the candidate layout. */}
              <Route path="/reference/:token" element={<RefereePage />} />

              <Route element={<RequireAuth />}>
                <Route element={<Layout />}>
                  <Route path="/" element={<Navigate to="/jobs" replace />} />
                  <Route path="/jobs" element={<JobsPage />} />
                  <Route path="/jobs/:id" element={<JobDetailPage />} />
                  <Route path="/applications" element={<ApplicationsPage />} />
                  <Route path="/applications/:id" element={<ApplicationDetailPage />} />
                  <Route path="/resumes" element={<ResumesPage />} />
                  <Route path="/evidence" element={<EvidencePage />} />
                  <Route path="/verification" element={<VerificationPage />} />
                  <Route path="/work-samples" element={<WorkSamplesPage />} />
                  <Route path="/work-samples/:assignmentId" element={<WorkSampleResponsePage />} />
                  <Route path="/interviews" element={<InterviewsPage />} />
                  <Route path="/notifications" element={<NotificationsPage />} />
                  <Route path="/profile" element={<ProfilePage />} />
                  <Route path="/privacy" element={<PrivacyPage />} />
                  <Route path="*" element={<NotFoundPage />} />
                </Route>
              </Route>
            </Routes>
          </AuthProvider>
        </NoticeProvider>
      </BrowserRouter>
    </QueryClientProvider>
  )
}
