import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { authApi } from '../../api/endpoints'
import { errorMessage } from '../../api/client'
import type { AuthUser } from '../../api/types'
import { useAuth } from '../../auth/AuthProvider'
import { Alert, Button, Spinner, buttonProps } from '../../design/components'
import { CircleAlert, CircleCheck, Icon } from '../../design/icons'
import { AuthHeading } from './AuthLayout'

type VerifyState = { phase: 'idle' | 'verifying' } | { phase: 'done'; user: AuthUser } | { phase: 'failed'; message: string }

export function VerifyEmailPage() {
  const [params] = useSearchParams()
  const token = params.get('token')?.trim() ?? ''
  const { status, user, updateUser } = useAuth()
  const [state, setState] = useState<VerifyState>({ phase: 'idle' })
  const attempted = useRef<string | null>(null)

  const resend = useMutation({ mutationFn: authApi.resendVerification })

  // Tokens are single-use: send each token once, even under React StrictMode's double effects.
  useEffect(() => {
    if (!token || attempted.current === token) return
    attempted.current = token
    setState({ phase: 'verifying' })
    authApi
      .verifyEmail(token)
      .then(verified => {
        setState({ phase: 'done', user: verified })
        updateUser(verified)
      })
      .catch(error => setState({ phase: 'failed', message: errorMessage(error) }))
  }, [token, updateUser])

  const signedIn = status === 'authenticated'
  const continueLink = signedIn ? (
    <Link {...buttonProps('primary', 'lg')} to="/home">
      Continue
    </Link>
  ) : (
    <Link {...buttonProps('primary', 'lg')} to="/login">
      Sign in
    </Link>
  )

  if (!token) {
    return (
      <div className="auth-state">
        <span className="auth-state-icon" data-tone="danger">
          <Icon icon={CircleAlert} size={20} />
        </span>
        <AuthHeading title="This link is incomplete">The confirmation link is missing its token. Open the link from the email again.</AuthHeading>
        {continueLink}
      </div>
    )
  }

  if (state.phase === 'done') {
    return (
      <div className="auth-state">
        <span className="auth-state-icon">
          <Icon icon={CircleCheck} size={20} />
        </span>
        <AuthHeading title="Email address confirmed">
          Thank you. <strong>{state.user.email}</strong> is confirmed, so every employer can accept your applications.
        </AuthHeading>
        {continueLink}
      </div>
    )
  }

  if (state.phase === 'failed') {
    return (
      <div className="auth-state">
        <span className="auth-state-icon" data-tone="danger">
          <Icon icon={CircleAlert} size={20} />
        </span>
        <AuthHeading title="We could not confirm this link">{state.message} Links expire after 24 hours and work once.</AuthHeading>
        {signedIn && user && !user.emailVerified ? (
          <>
            <Button variant="primary" size="lg" loading={resend.isPending} disabled={resend.isSuccess} onClick={() => resend.mutate()}>
              {resend.isSuccess ? 'Link sent' : 'Send a new link'}
            </Button>
            <div aria-live="polite">
              {resend.isSuccess && <Alert tone="success">A new link is on its way to {user.email}.</Alert>}
              {resend.isError && <Alert tone="danger">{errorMessage(resend.error)}</Alert>}
            </div>
          </>
        ) : (
          !signedIn && <p className="auth-intro">Sign in to request a new confirmation link.</p>
        )}
        <p className="auth-switch">
          <Link to={signedIn ? '/home' : '/login'}>{signedIn ? 'Go to your overview' : 'Go to sign in'}</Link>
        </p>
      </div>
    )
  }

  return (
    <div className="auth-state">
      <AuthHeading title="Confirming your email address" />
      <p className="auth-wait" role="status">
        <Spinner size={14} /> Checking your link…
      </p>
    </div>
  )
}
