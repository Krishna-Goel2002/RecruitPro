import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './AuthProvider'
import { FullPageLoading } from '../components/Feedback'
import { Spinner } from '../design/components'

export interface RedirectState {
  from?: string
}

/** Where to go after signing in: the page that sent the visitor to /login, if it is a safe in-app path. */
export function redirectTarget(state: unknown): string {
  const from = (state as RedirectState | null)?.from
  return typeof from === 'string' && from.startsWith('/') && !from.startsWith('//') && !from.startsWith('/login') ? from : '/home'
}

/** Routes that need a signed-in candidate. */
export function RequireAuth() {
  const { status } = useAuth()
  const location = useLocation()
  if (status === 'loading') return <FullPageLoading label="Restoring your session…" />
  if (status === 'anonymous') {
    const state: RedirectState = { from: `${location.pathname}${location.search}` }
    return <Navigate to="/login" replace state={state} />
  }
  return <Outlet />
}

/** Sign-in, registration and password-reset requests: signed-in users continue to where they were going. */
export function PublicOnly() {
  const { status } = useAuth()
  const location = useLocation()
  // Rendered inside <AuthLayout>, which already provides the <main> landmark.
  if (status === 'loading') {
    return (
      <p className="auth-wait" role="status">
        <Spinner size={14} /> Restoring your session…
      </p>
    )
  }
  if (status === 'authenticated') return <Navigate to={redirectTarget(location.state)} replace />
  return <Outlet />
}
