import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { redirectTarget } from '../../auth/guards'
import { useAuth, type SignedOutReason } from '../../auth/AuthProvider'
import { Field, PasswordInput } from '../../components/Form'
import { FormError } from '../../components/Feedback'
import { AuthHeading } from './AuthLayout'

const REASON_TEXT: Record<SignedOutReason, string> = {
  'signed-out': 'You have signed out.',
  expired: 'Your session has ended. Please sign in again.',
  'staff-session':
    'This browser is signed in to the HR portal. Sign in with a candidate account to use the candidate portal.',
  unreachable: 'We could not reach the server to restore your session.',
  'account-deleted': 'Your account has been deleted. Thank you for using RecruitPro.',
  'password-reset': 'Your password was changed. Please sign in with your new password.',
}

export function LoginPage() {
  const { login, reason, restore } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login({ email: email.trim(), password })
      // <PublicOnly> redirects to the same target as soon as the session exists; this covers the same case explicitly.
      navigate(redirectTarget(location.state), { replace: true })
    } catch (caught) {
      setError(caught)
      setBusy(false)
    }
  }

  return (
    <>
      <AuthHeading title="Welcome back">Find your next opportunity and keep your applications in one place.</AuthHeading>
      {reason && (
        <div className={`alert ${reason === 'unreachable' || reason === 'expired' ? 'warning' : 'info'}`} role="status">
          <span>{REASON_TEXT[reason]}</span>
          {reason === 'unreachable' && (
            <button type="button" className="text-button inline" onClick={restore}>
              Try again
            </button>
          )}
        </div>
      )}
      <form className="stack-form" onSubmit={submit} noValidate={false}>
        <Field label="Email" required>
          {control => (
            <input
              {...control}
              type="email"
              autoComplete="email"
              inputMode="email"
              maxLength={255}
              value={email}
              onChange={event => setEmail(event.target.value)}
            />
          )}
        </Field>
        <Field label="Password" required>
          {control => <PasswordInput control={control} value={password} onChange={setPassword} autoComplete="current-password" />}
        </Field>
        <FormError error={error} />
        <button className="primary" type="submit" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
      <div className="auth-links">
        <Link to="/forgot-password">Forgot your password?</Link>
        <Link to="/register">New here? Create an account</Link>
      </div>
    </>
  )
}
