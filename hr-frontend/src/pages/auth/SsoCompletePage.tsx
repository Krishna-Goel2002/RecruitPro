import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { errorMessage } from '../../api/client'
import { useAuth } from '../../auth/AuthContext'
import { homePath } from '../../auth/permissions'
import { usePageTitle } from '../../lib/hooks'
import { AuthError, AuthLayout } from './AuthLayout'

/**
 * /sso/complete — the SSO callback set the staff refresh cookie and redirected here. Exchange it for an access token
 * (refresh with the staff portal header) and go to the user's home screen.
 */
export function SsoCompletePage() {
  usePageTitle('Signing in')
  const { completeExternalSignIn } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)
  const started = useRef(false)

  useEffect(() => {
    if (started.current) return
    started.current = true
    completeExternalSignIn()
      .then(user => {
        if (user) navigate(homePath(user.role), { replace: true })
        else setError('Single sign-on did not create a session. Please try again or sign in with your password.')
      })
      .catch(cause => setError(errorMessage(cause)))
  }, [completeExternalSignIn, navigate])

  return (
    <AuthLayout eyebrow="SINGLE SIGN-ON" title={error ? 'Sign-in not completed' : 'Signing you in…'} intro={error ? '' : 'One moment while we finish single sign-on.'}>
      {error ? (
        <>
          <AuthError message={error} />
          <Link className="primary-button" to="/login">Back to sign in</Link>
        </>
      ) : (
        <p className="auth-info" role="status"><span className="spinner" aria-hidden="true" /> Completing sign-in…</p>
      )}
    </AuthLayout>
  )
}
