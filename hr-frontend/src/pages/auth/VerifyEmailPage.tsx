import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { authApi } from '../../api/auth'
import { errorMessage } from '../../api/client'
import type { AuthUser } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { usePageTitle } from '../../lib/hooks'
import { AuthError, AuthLayout, AuthSuccess } from './AuthLayout'

type VerifyState =
  | { phase: 'idle' | 'verifying' }
  | { phase: 'done'; user: AuthUser }
  | { phase: 'failed'; message: string }

/**
 * /verify-email?token=… — target of the verification e-mail sent to staff (organisation sign-up). Same behaviour and
 * endpoint as the candidate portal: POST /auth/verify-email once per token; on failure a signed-in user can ask for a
 * new link (POST /auth/resend-verification).
 */
export function VerifyEmailPage() {
  usePageTitle('Verify your email')
  const [params] = useSearchParams()
  const token = params.get('token')?.trim() ?? ''
  const { status } = useAuth()
  const [state, setState] = useState<VerifyState>({ phase: 'idle' })
  const [resend, setResend] = useState<'idle' | 'sending' | 'sent' | { error: string }>('idle')
  const attempted = useRef<string | null>(null)

  // Tokens are single-use: send each token once, even under React StrictMode's double effects.
  useEffect(() => {
    if (!token || attempted.current === token) return
    attempted.current = token
    setState({ phase: 'verifying' })
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
  const footer = signedIn ? <Link to="/">Continue to RecruitPro</Link> : <Link to="/login">Go to sign in</Link>

  return (
    <AuthLayout eyebrow="ACCOUNT" title="Verify your email address" intro="Confirming your work email keeps account recovery safe." footer={footer}>
      {!token ? (
        <AuthError message="The verification link is missing its token. Open the link from the email again." />
      ) : state.phase === 'done' ? (
        <AuthSuccess>Thank you. <strong>{state.user.email}</strong> is now verified.</AuthSuccess>
      ) : state.phase === 'failed' ? (
        <>
          <AuthError message={`${state.message} Links expire after 24 hours and work once.`} />
          {signedIn ? (
            <div className="sign-in-form">
              <button className="primary-button" type="button" onClick={() => void sendNewLink()} disabled={resend === 'sending' || resend === 'sent'}>
                {resend === 'sending' ? 'Sending…' : resend === 'sent' ? 'Link sent' : 'Send a new link'}
              </button>
              <p className="muted-copy" aria-live="polite">
                {resend === 'sent' ? 'A new link is on its way to your inbox.' : typeof resend === 'object' ? resend.error : ''}
              </p>
            </div>
          ) : (
            <p className="muted-copy">Sign in to request a new verification link.</p>
          )}
        </>
      ) : (
        <p className="muted-copy" role="status" aria-live="polite">Checking your link…</p>
      )}
    </AuthLayout>
  )
}
