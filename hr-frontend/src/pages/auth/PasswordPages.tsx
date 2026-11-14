import { FormEvent, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { authApi } from '../../api/auth'
import { errorMessage } from '../../api/client'
import { ArrowIcon, LockIcon, MailIcon } from '../../components/Icons'
import { passwordProblem } from '../../lib/format'
import { usePageTitle } from '../../lib/hooks'
import { AuthError, AuthInput, AuthLayout, AuthSuccess } from './AuthLayout'

/** /forgot-password: always answers the same way so it does not reveal whether an account exists. */
export function ForgotPasswordPage() {
  usePageTitle('Forgot password')
  const [email, setEmail] = useState('')
  const [busy, setBusy] = useState(false)
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (!/^\S+@\S+\.\S+$/.test(email.trim())) return setError('Enter the email address of your account.')
    setBusy(true)
    try {
      await authApi.forgotPassword(email.trim())
      setSent(true)
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthLayout eyebrow="ACCOUNT RECOVERY" title="Reset your password" intro="We will email a link that is valid for one hour." footer={<Link to="/login">Back to sign in</Link>}>
      {sent ? (
        <AuthSuccess>If an account exists for <strong>{email.trim()}</strong>, a reset link is on its way. Check your inbox and spam folder.</AuthSuccess>
      ) : (
        <form className="sign-in-form" onSubmit={event => void submit(event)} noValidate>
          <AuthInput label="Work email" icon={<MailIcon className="input-icon" />} type="email" autoComplete="email" maxLength={255} value={email} onChange={event => setEmail(event.target.value)} disabled={busy} required />
          <AuthError message={error} />
          <button className="primary-button" type="submit" disabled={busy}>{busy ? 'Sending…' : <>Send reset link <ArrowIcon /></>}</button>
        </form>
      )}
    </AuthLayout>
  )
}

/** /reset-password?token=… */
export function ResetPasswordPage() {
  usePageTitle('Choose a new password')
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    const next: Record<string, string> = {}
    const problem = passwordProblem(password)
    if (problem) next.password = problem
    if (confirm !== password) next.confirm = 'The passwords do not match.'
    setErrors(next)
    if (Object.keys(next).length) return
    setBusy(true)
    try {
      await authApi.resetPassword({ token, newPassword: password })
      setDone(true)
    } catch (cause) {
      setError(errorMessage(cause, 'This reset link is invalid or has expired. Request a new one.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthLayout eyebrow="ACCOUNT RECOVERY" title="Choose a new password" intro="Other sessions are signed out when your password changes." footer={<Link to="/login">Back to sign in</Link>}>
      {!token ? (
        <AuthError message="This reset link is incomplete. Open the link from your email again, or request a new one." />
      ) : done ? (
        <AuthSuccess>Your password was changed. <Link to="/login">Sign in with your new password</Link>.</AuthSuccess>
      ) : (
        <form className="sign-in-form" onSubmit={event => void submit(event)} noValidate>
          <AuthInput label="New password" icon={<LockIcon className="input-icon" />} type="password" revealable autoComplete="new-password" maxLength={72} value={password} onChange={event => setPassword(event.target.value)} error={errors.password} hint="10–72 characters with at least one letter and one digit." disabled={busy} required />
          <AuthInput label="Confirm new password" icon={<LockIcon className="input-icon" />} type="password" autoComplete="new-password" maxLength={72} value={confirm} onChange={event => setConfirm(event.target.value)} error={errors.confirm} disabled={busy} required />
          <AuthError message={error} />
          <button className="primary-button" type="submit" disabled={busy}>{busy ? 'Saving…' : <>Save new password <ArrowIcon /></>}</button>
        </form>
      )}
    </AuthLayout>
  )
}
