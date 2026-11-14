import { FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { authApi } from '../../api/auth'
import { errorMessage, isApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthContext'
import { ArrowIcon, LockIcon, MailIcon } from '../../components/Icons'
import { usePageTitle } from '../../lib/hooks'
import { AuthError, AuthInput, AuthLayout } from './AuthLayout'

export function LoginPage() {
  usePageTitle('Sign in')
  const { acceptSession, endedReason } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (!email.trim()) return setError('Enter your work email address.')
    if (!password) return setError('Enter your password.')
    setBusy(true)
    try {
      const auth = await authApi.login({ email: email.trim(), password })
      await acceptSession(auth)
      // PublicOnly redirects to the page the user came from, or their home screen.
    } catch (cause) {
      setError(isApiError(cause, 401) ? 'Email or password is incorrect, or the account is locked for a few minutes.' : errorMessage(cause))
      setPassword('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthLayout
      eyebrow="PRIVATE HR ACCESS"
      title="Welcome back"
      intro="Sign in with your RecruitPro work account to continue."
      footer={<>
        <Link to="/forgot-password">Forgot your password?</Link>
        <span>New organisation? <Link to="/signup">Create a workspace</Link></span>
      </>}
    >
      {endedReason && !error && <p className="auth-info" role="status">{endedReason}</p>}
      <form className="sign-in-form" onSubmit={event => void submit(event)} noValidate>
        <AuthInput label="Work email" icon={<MailIcon className="input-icon" />} type="email" autoComplete="username" inputMode="email" maxLength={255} placeholder="you@company.com" value={email} onChange={event => setEmail(event.target.value)} disabled={busy} required />
        <AuthInput label="Password" icon={<LockIcon className="input-icon" />} type="password" revealable autoComplete="current-password" maxLength={72} placeholder="Enter your password" value={password} onChange={event => setPassword(event.target.value)} disabled={busy} required />
        <AuthError message={error} />
        <button className="primary-button" type="submit" disabled={busy}>
          {busy ? <><span className="spinner" aria-hidden="true" />Signing in…</> : <>Sign in to HR workspace <ArrowIcon /></>}
        </button>
      </form>
      <div className="form-security-note"><LockIcon className="lock-icon" /><p>Authorised staff only. The server checks your role for every action.</p></div>
    </AuthLayout>
  )
}
