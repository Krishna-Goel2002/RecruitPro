import { FormEvent, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { authApi } from '../../api/auth'
import { errorMessage, isApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthContext'
import { homePath } from '../../auth/permissions'
import { ArrowIcon, LockIcon, UserIcon } from '../../components/Icons'
import { LoadingPanel } from '../../components/States'
import { formatDateTime, passwordProblem, roleLabel } from '../../lib/format'
import { usePageTitle } from '../../lib/hooks'
import { AuthError, AuthInput, AuthLayout } from './AuthLayout'

/** /accept-invite?token=… : preview the invitation, then set name and password. */
export function AcceptInvitePage() {
  usePageTitle('Accept invitation')
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const navigate = useNavigate()
  const { acceptSession } = useAuth()
  const preview = useQuery({
    queryKey: ['invitation-preview', token],
    queryFn: () => authApi.previewInvitation(token),
    enabled: token.length > 0,
    retry: false,
  })
  const [firstName, setFirstName] = useState('')
  const [lastName, setLastName] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    const next: Record<string, string> = {}
    if (!firstName.trim()) next.firstName = 'Enter your first name.'
    if (!lastName.trim()) next.lastName = 'Enter your last name.'
    const problem = passwordProblem(password)
    if (problem) next.password = problem
    if (confirm !== password) next.confirm = 'The passwords do not match.'
    setErrors(next)
    if (Object.keys(next).length) return
    setBusy(true)
    try {
      const auth = await authApi.acceptInvite({ token, firstName: firstName.trim(), lastName: lastName.trim(), password })
      const user = await acceptSession(auth)
      navigate(homePath(user.role), { replace: true })
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  const invalid = !token || isApiError(preview.error, 404)

  return (
    <AuthLayout
      eyebrow="INVITATION"
      title={preview.data ? `Join ${preview.data.organizationName}` : 'Accept your invitation'}
      intro={preview.data
        ? <>You were invited as <strong>{roleLabel(preview.data.role)}</strong> with <strong>{preview.data.email}</strong>. The invitation expires {formatDateTime(preview.data.expiresAt)}.</>
        : 'Set your name and password to join your organisation’s workspace.'}
      footer={<span>Already have an account? <Link to="/login">Sign in</Link></span>}
    >
      {preview.isLoading && <LoadingPanel label="Checking your invitation…" compact />}
      {invalid && <AuthError message="This invitation link is invalid, expired, already used or revoked. Ask your administrator for a new one." />}
      {preview.error && !invalid && <AuthError message={errorMessage(preview.error)} />}
      {preview.data && (
        <form className="sign-in-form" onSubmit={event => void submit(event)} noValidate>
          <div className="auth-two-col">
            <AuthInput label="First name" icon={<UserIcon className="input-icon" />} autoComplete="given-name" maxLength={100} value={firstName} onChange={event => setFirstName(event.target.value)} error={errors.firstName} disabled={busy} required />
            <AuthInput label="Last name" autoComplete="family-name" maxLength={100} value={lastName} onChange={event => setLastName(event.target.value)} error={errors.lastName} disabled={busy} required />
          </div>
          <AuthInput label="Password" icon={<LockIcon className="input-icon" />} type="password" revealable autoComplete="new-password" maxLength={72} value={password} onChange={event => setPassword(event.target.value)} error={errors.password} hint="10–72 characters with at least one letter and one digit." disabled={busy} required />
          <AuthInput label="Confirm password" icon={<LockIcon className="input-icon" />} type="password" autoComplete="new-password" maxLength={72} value={confirm} onChange={event => setConfirm(event.target.value)} error={errors.confirm} disabled={busy} required />
          <AuthError message={error} />
          <button className="primary-button" type="submit" disabled={busy}>
            {busy ? <><span className="spinner" aria-hidden="true" />Joining…</> : <>Join workspace <ArrowIcon /></>}
          </button>
        </form>
      )}
    </AuthLayout>
  )
}
