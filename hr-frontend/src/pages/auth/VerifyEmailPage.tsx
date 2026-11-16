import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { authApi } from '../../api/auth'
import { errorMessage } from '../../api/client'
import type { AuthUser } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { Button, Spinner, buttonProps } from '../../design/components'
import { AuthLayout, AuthNotice } from './AuthLayout'

type VerifyState = { phase: 'verifying' } | { phase: 'done'; user: AuthUser } | { phase: 'failed'; message: string }

/**
 * /verify-email?token=… — target of the verification e-mail sent to staff (organisation sign-up). POST
 * /auth/verify-email once per token; on failure a signed-in user can ask for a new link in place.
 */
export function VerifyEmailPage() {
  const [params] = useSearchParams()
  const token = params.get('token')?.trim() ?? ''
  const { status } = useAuth()
  const [state, setState] = useState<VerifyState>({ phase: 'verifying' })
  const [resend, setResend] = useState<'idle' | 'sending' | 'sent' | { error: string }>('idle')
  const attempted = useRef<string | null>(null)

  // Tokens are single-use: send each token once, even under React StrictMode's double effects.
  useEffect(() => {
    if (!token || attempted.current === token) return
    attempted.current = token
    authApi
      .verifyEmail(token)
      .then(verified => setState({ phase: 'done', user: verified }))
      .catch(cause => setState({ phase: 'failed', message: errorMessage(cause) }))
  }, [token])

  async function sendNewLink() {
    setResend('sending')
    try {
      await authApi.resendVerification()
      setResend('sent')
    } catch (cause) {
      setResend({ error: errorMessage(cause) })
    }
  }

  const signedIn = status === 'authenticated'
  const next = signedIn
    ? <Link {...buttonProps('primary', 'lg')} data-block="" to="/">Continue to your workspace</Link>
    : <Link {...buttonProps('primary', 'lg')} data-block="" to="/login">Sign in</Link>

  if (!token) {
    return (
      <AuthLayout title="This link is incomplete" intro="The verification link is missing its code. Open the link from the email again.">
        <div className="hra-auth-actions">{next}</div>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout
      stepKey={state.phase}
      title={state.phase === 'done' ? 'Email confirmed' : state.phase === 'failed' ? 'This link did not work' : 'Confirming your email'}
      intro={state.phase === 'done'
        ? <><strong>{state.user.email}</strong> is confirmed. Account recovery emails will reach you there.</>
        : state.phase === 'failed' ? 'Verification links work once and expire after 24 hours.' : undefined}
    >
      {state.phase === 'verifying' && <p className="hra-auth-state" role="status"><Spinner size={16} /> Checking your link…</p>}
      {state.phase === 'done' && <div className="hra-auth-actions">{next}</div>}
      {state.phase === 'failed' && (
        <>
          <AuthNotice tone="danger">{state.message}</AuthNotice>
          <div className="hra-auth-actions">
            {signedIn ? (
              <>
                <Button variant="primary" size="lg" loading={resend === 'sending'} onClick={() => void sendNewLink()} disabled={resend === 'sent'}>
                  {resend === 'sent' ? 'New link sent' : 'Send a new link'}
                </Button>
                <Link {...buttonProps('ghost', 'lg')} to="/">Continue to your workspace</Link>
              </>
            ) : next}
          </div>
          <p className="hra-auth-intro" role="status" aria-live="polite">
            {resend === 'sent' ? 'A new link is on its way to your inbox.' : typeof resend === 'object' ? resend.error : signedIn ? '' : 'Sign in to request a new verification link.'}
          </p>
        </>
      )}
    </AuthLayout>
  )
}
