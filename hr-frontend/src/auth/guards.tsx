import { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from './AuthContext'
import { Capability, homePath } from './permissions'
import { ForbiddenPage } from '../pages/StatusPages'
import { FullPageLoader } from '../components/States'

/** Requires a staff session; optionally a capability. Unauthenticated users go to /login and come back afterwards. */
export function RequireAuth({ capability, children }: { capability?: Capability; children: ReactNode }) {
  const { status, can, signedOutByUser } = useAuth()
  const location = useLocation()
  if (status === 'restoring') return <FullPageLoader label="Restoring your session…" />
  if (status === 'anonymous') {
    const from = `${location.pathname}${location.search}`
    return <Navigate to="/login" replace state={signedOutByUser ? undefined : { from }} />
  }
  if (capability && !can(capability)) return <ForbiddenPage />
  return <>{children}</>
}

/** Public account pages: a signed-in user is sent back where they came from, or to their home screen. */
export function PublicOnly({ children }: { children: ReactNode }) {
  const { status, user } = useAuth()
  const location = useLocation()
  if (status === 'restoring') return <FullPageLoader label="Restoring your session…" />
  if (status === 'authenticated' && user) {
    const from = (location.state as { from?: unknown } | null)?.from
    const target = typeof from === 'string' && from.startsWith('/') && !from.startsWith('//') ? from : homePath(user.role)
    return <Navigate to={target} replace />
  }
  return <>{children}</>
}

export function HomeRedirect() {
  const { status, user } = useAuth()
  if (status === 'restoring') return <FullPageLoader label="Restoring your session…" />
  if (status === 'authenticated' && user) return <Navigate to={homePath(user.role)} replace />
  return <Navigate to="/login" replace />
}
