import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { errorMessage } from '../../api/client'
import { useAuth } from '../../auth/AuthContext'
import { homePath } from '../../auth/permissions'
import { Spinner, buttonProps } from '../../design/components'
import { AuthLayout, AuthNotice } from './AuthLayout'

/**
 * /sso/complete — the SSO callback set the staff refresh cookie and redirected here. Exchange it for an access token
 * (refresh with the staff portal header) and go to the user's home screen.
 */
export function SsoCompletePage() {
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
        else setError('Single sign-on did not create a session. Try again, or sign in with your password.')
      })
      .catch(cause => setError(errorMessage(cause)))
  }, [completeExternalSignIn, navigate])

  return (
    <AuthLayout
      stepKey={error ? 'error' : 'busy'}
      title={error ? 'Sign-in not completed' : 'Signing you in'}
      intro={error ? undefined : 'Finishing single sign-on with your identity provider.'}
    >
      {error ? (
        <>
          <AuthNotice tone="danger">{error}</AuthNotice>
          <div className="hra-auth-actions"><Link {...buttonProps('primary', 'lg')} data-block="" to="/login">Back to sign in</Link></div>
        </>
      ) : (
        <p className="hra-auth-state" role="status"><Spinner size={16} /> Completing sign-in…</p>
      )}
    </AuthLayout>
  )
}
