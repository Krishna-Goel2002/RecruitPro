import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { authApi } from '../../api/endpoints'
import { errorMessage } from '../../api/client'
import type { AuthUser } from '../../api/types'
import { useAuth } from '../../auth/AuthProvider'
import { LoadingBlock } from '../../components/Feedback'
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

  const continueLink = status === 'authenticated' ? (
    <Link to="/jobs">Continue to jobs</Link>
  ) : (
    <Link to="/login">Go to sign in</Link>
  )

  if (!token) {
    return (
      <>
        <AuthHeading title="This link is incomplete" />
        <p className="alert error" role="alert">
          The verification link is missing its token. Open the link from the email again.
        </p>
        <div className="auth-links">{continueLink}</div>
      </>
    )
  }

  if (state.phase === 'done') {
    return (
      <>
        <AuthHeading title="Email address verified" />
        <p className="alert success" role="status">
          Thank you. {state.user.email} is now verified.
        </p>
        <div className="auth-links">{continueLink}</div>
      </>
    )
  }

  if (state.phase === 'failed') {
    return (
      <>
        <AuthHeading title="We could not verify this link" />
        <p className="alert error" role="alert">
          {state.message} Links expire after 24 hours and work once.
        </p>
        {status === 'authenticated' && user && !user.emailVerified && (
          <div className="stack-form">
            <button type="button" className="primary" onClick={() => resend.mutate()} disabled={resend.isPending || resend.isSuccess}>
              {resend.isPending ? 'Sending…' : resend.isSuccess ? 'Link sent' : 'Send a new link'}
            </button>
            <p className="muted" aria-live="polite">
              {resend.isSuccess ? `A new link is on its way to ${user.email}.` : ''}
              {resend.isError ? errorMessage(resend.error) : ''}
            </p>
          </div>
        )}
        {status !== 'authenticated' && <p className="muted">Sign in to request a new verification link.</p>}
        <div className="auth-links">{continueLink}</div>
      </>
    )
  }

  return (
    <>
      <AuthHeading title="Verifying your email address" />
      <LoadingBlock label="Checking your link…" />
    </>
  )
}
