import { lazy, Suspense, type ComponentType } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { ApiError } from './api/client'
import { AuthProvider } from './auth/AuthProvider'
import { PublicOnly, RequireAuth } from './auth/guards'
import { Layout } from './components/Layout'
import { ToastProvider } from './design/components'
import { AuthLayout } from './pages/auth/AuthLayout'
import { LoginPage } from './pages/auth/LoginPage'

/** Route-level code splitting (DESIGN_SYSTEM §13 budget): each page loads on first visit; the shell stays put. */
function page<K extends string>(load: () => Promise<Record<K, ComponentType>>, name: K) {
  return lazy(() => load().then(m => ({ default: m[name] })))
}

const RegisterPage = page(() => import('./pages/auth/RegisterPage'), 'RegisterPage')
const ForgotPasswordPage = page(() => import('./pages/auth/ForgotPasswordPage'), 'ForgotPasswordPage')
const ResetPasswordPage = page(() => import('./pages/auth/ResetPasswordPage'), 'ResetPasswordPage')
const VerifyEmailPage = page(() => import('./pages/auth/VerifyEmailPage'), 'VerifyEmailPage')
const HomePage = page(() => import('./pages/HomePage'), 'HomePage')
const JobsPage = page(() => import('./pages/JobsPage'), 'JobsPage')
const JobDetailPage = page(() => import('./pages/JobDetailPage'), 'JobDetailPage')
const ApplicationsPage = page(() => import('./pages/ApplicationsPage'), 'ApplicationsPage')
const ApplicationDetailPage = page(() => import('./pages/ApplicationDetailPage'), 'ApplicationDetailPage')
const ResumesPage = page(() => import('./pages/ResumesPage'), 'ResumesPage')
const EvidencePage = page(() => import('./pages/EvidencePage'), 'EvidencePage')
const VerificationPage = page(() => import('./pages/VerificationPage'), 'VerificationPage')
const WorkSamplesPage = page(() => import('./pages/WorkSamplesPage'), 'WorkSamplesPage')
const WorkSampleResponsePage = page(() => import('./pages/WorkSampleResponsePage'), 'WorkSampleResponsePage')
const InterviewsPage = page(() => import('./pages/InterviewsPage'), 'InterviewsPage')
const NotificationsPage = page(() => import('./pages/NotificationsPage'), 'NotificationsPage')
const ProfilePage = page(() => import('./pages/ProfilePage'), 'ProfilePage')
const PrivacyPage = page(() => import('./pages/PrivacyPage'), 'PrivacyPage')
const RefereePage = page(() => import('./pages/RefereePage'), 'RefereePage')
const NotFoundPage = page(() => import('./pages/NotFoundPage'), 'NotFoundPage')

// Living style guide (hidden route, not linked from navigation). Lazy, so it never weighs on the app bundle.
const DesignGallery = lazy(() => import('./design/gallery/DesignGallery'))

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
        <ToastProvider>
          <AuthProvider>
            <Routes>
              <Route
                path="/__design"
                element={
                  <Suspense fallback={null}>
                    <DesignGallery portal="candidate" />
                  </Suspense>
                }
              />

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
              <Route
                path="/reference/:token"
                element={
                  <Suspense fallback={null}>
                    <RefereePage />
                  </Suspense>
                }
              />

              <Route element={<RequireAuth />}>
                <Route element={<Layout />}>
                  <Route path="/" element={<Navigate to="/home" replace />} />
                  <Route path="/home" element={<HomePage />} />
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
        </ToastProvider>
      </BrowserRouter>
    </QueryClientProvider>
  )
}
