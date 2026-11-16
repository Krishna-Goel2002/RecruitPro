import { useRef, useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { redirectTarget } from '../../auth/guards'
import { useAuth, type SignedOutReason } from '../../auth/AuthProvider'
import { FormError } from '../../components/Feedback'
import { PasswordInput, focusFirstInvalid } from '../../components/Form'
import brand from '../../design/brand/active'
import { Alert, Button, Field, Input } from '../../design/components'
import { AuthHeading } from './AuthLayout'

const REASON_TEXT: Record<SignedOutReason, string> = {
  'signed-out': 'You have signed out.',
  expired: 'Your session has ended. Please sign in again.',
  'staff-session': 'This browser is signed in to the HR portal. Sign in with a candidate account to use the candidate portal.',
  unreachable: 'We could not reach the server to restore your session.',
  'account-deleted': `Your account has been deleted. Thank you for using ${brand.name}.`,
  'password-reset': 'Your password was changed. Please sign in with your new password.',
}

const EMAIL = /^\S+@\S+\.\S+$/

export function LoginPage() {
  const { login, reason, restore } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const formRef = useRef<HTMLFormElement>(null)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState<{ email?: string; password?: string }>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const next: typeof errors = {}
    if (!EMAIL.test(email.trim())) next.email = 'Enter the email address you registered with.'
    if (!password) next.password = 'Enter your password.'
    setErrors(next)
    if (next.email || next.password) return focusFirstInvalid(formRef.current)
    setBusy(true)
    setError(null)
    try {
      await login({ email: email.trim(), password })
      navigate(redirectTarget(location.state), { replace: true })
    } catch (caught) {
      setError(caught)
      setBusy(false)
    }
  }

  const reasonTone = reason === 'unreachable' || reason === 'expired' ? 'warning' : reason === 'account-deleted' ? 'neutral' : 'info'

  return (
    <>
      <AuthHeading title="Sign in">Pick up where you left off: your applications, tasks and evidence.</AuthHeading>
      {reason && (
        <Alert
          tone={reasonTone}
          live
          className="auth-alert"
          action={
            reason === 'unreachable' ? (
              <Button size="sm" variant="secondary" onClick={restore}>
                Try again
              </Button>
            ) : undefined
          }
        >
          {REASON_TEXT[reason]}
        </Alert>
      )}
      <form ref={formRef} onSubmit={submit} noValidate>
        <div className="auth-fields">
          <Field label="Email" error={errors.email}>
            <Input
              type="email"
              autoComplete="email"
              inputMode="email"
              maxLength={255}
              controlSize="lg"
              value={email}
              autoFocus
              onChange={event => setEmail(event.target.value)}
              onBlur={() => email && !EMAIL.test(email.trim()) && setErrors(c => ({ ...c, email: 'Check the email address.' }))}
            />
          </Field>
          <div>
            <Field label="Password" error={errors.password}>
              <PasswordInput controlSize="lg" value={password} onChange={setPassword} autoComplete="current-password" />
            </Field>
            <p className="auth-forgot">
              <Link to="/forgot-password" className="auth-inline-link">
                Forgot your password?
              </Link>
            </p>
          </div>
          <FormError error={error} />
          <div className="auth-actions">
            <Button type="submit" variant="primary" size="lg" block loading={busy}>
              Sign in
            </Button>
          </div>
        </div>
      </form>
      <p className="auth-switch">
        New here? <Link to="/register">Create an account</Link>
      </p>
    </>
  )
}
