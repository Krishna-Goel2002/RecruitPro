import { FormEvent, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { authApi } from '../../api/auth'
import { errorMessage, isApiError } from '../../api/client'
import { AuthResponse, MfaChallenge, isMfaChallenge } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { homePath } from '../../auth/permissions'
import { passwordError } from '../../components/PasswordChecklist'
import { Button, Field, Input, Skeleton, buttonProps } from '../../design/components'
import { formatDate, relativeTime } from '../../design/lib/format'
import { roleLabel } from '../../lib/format'
import { AuthLayout, AuthNotice } from './AuthLayout'
import { EnrollStep, MfaStep, RecoveryCodesStep } from './MfaSteps'
import { NewPasswordField } from './PasswordPages'

type Step =
  | { kind: 'form' }
  | { kind: 'mfa'; challenge: MfaChallenge }
  | { kind: 'enroll'; challenge: MfaChallenge }
  | { kind: 'codes'; recoveryCodes: string[]; session: AuthResponse }
  | { kind: 'signin'; message: string | null }

/**
 * /accept-invite?token=… : the invitation as facts, then name and password; lands on the role's home screen. In an
 * organisation that requires two-step verification the server answers like a login (R1-M4: `mfaRequired`, no session):
 * the same enrolment (or code) step as /login follows, and the recovery codes are shown before the workspace opens.
 */
export function AcceptInvitePage() {
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
  const [errors, setErrors] = useState<Record<string, string | null>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [step, setStep] = useState<Step>({ kind: 'form' })

  async function adopt(auth: AuthResponse) {
    const user = await acceptSession(auth)
    navigate(homePath(user.role), { replace: true })
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    const next: Record<string, string | null> = {
      firstName: firstName.trim() ? null : 'Enter your first name.',
      lastName: lastName.trim() ? null : 'Enter your last name.',
      password: passwordError(password),
    }
    setErrors(next)
    if (Object.values(next).some(Boolean)) {
      window.setTimeout(() => document.querySelector<HTMLElement>('.hra-auth-form [aria-invalid="true"]')?.focus(), 0)
      return
    }
    setBusy(true)
    try {
      const result = await authApi.acceptInvite({ token, firstName: firstName.trim(), lastName: lastName.trim(), password })
      setPassword('')
      if (isMfaChallenge(result)) {
        setStep(result.mfaEnrollmentRequired ? { kind: 'enroll', challenge: result } : { kind: 'mfa', challenge: result })
        return
      }
      await adopt(result)
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  // The account exists once the invitation is accepted, so leaving the second step means signing in normally.
  const toSignIn = (message?: string) => setStep({ kind: 'signin', message: message ?? null })
  if (step.kind === 'enroll') {
    return <EnrollStep challenge={step.challenge} backLabel="Sign in later" onRestart={toSignIn}
      onConfirmed={(recoveryCodes, session) => setStep({ kind: 'codes', recoveryCodes, session })} />
  }
  if (step.kind === 'mfa') {
    return <MfaStep challenge={step.challenge} email={preview.data?.email ?? ''} backLabel="Sign in later" onSession={adopt} onRestart={toSignIn} />
  }
  if (step.kind === 'codes') {
    return <RecoveryCodesStep codes={step.recoveryCodes} email={preview.data?.email ?? ''} onContinue={() => adopt(step.session)} />
  }
  if (step.kind === 'signin') {
    return (
      <AuthLayout title="Your account is ready" intro="Your invitation was accepted. Sign in with your email and the password you just chose; two-step verification continues there.">
        {step.message && <AuthNotice tone="warning">{step.message.replace(/ Enter your password again\.$/, '').replace(/; then enter your password again\.$/, '.')}</AuthNotice>}
        <div className="hra-auth-actions">
          <Link {...buttonProps('primary', 'lg')} to="/login">Go to sign in</Link>
        </div>
      </AuthLayout>
    )
  }

  const invalid = !token || isApiError(preview.error, 404) || isApiError(preview.error, 410) || isApiError(preview.error, 400)
  const data = preview.data

  if (invalid) {
    return (
      <AuthLayout title="This invitation cannot be used" intro="It may have expired (links last 7 days), already been used, or been revoked by your administrator.">
        <div className="hra-auth-actions">
          <Link {...buttonProps('secondary', 'lg')} to="/login">Go to sign in</Link>
        </div>
        <p className="hra-auth-intro">Ask your administrator to send a new invitation.</p>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout
      title={data ? `Join ${data.organizationName}` : 'Accept your invitation'}
      intro="Choose your name and a password to finish setting up your account."
      footer={<span>Already have an account? <Link to="/login">Sign in</Link></span>}
    >
      {preview.isLoading ? (
        <div className="hra-invite-facts" aria-busy="true">
          {[0, 1, 2].map(i => <Skeleton key={i} width="100%" height={12} style={{ gridColumn: '1 / -1' }} />)}
          <span className="sr-only" role="status">Checking your invitation…</span>
        </div>
      ) : preview.error ? (
        <AuthNotice tone="danger">{errorMessage(preview.error)}</AuthNotice>
      ) : data ? (
        <dl className="hra-invite-facts">
          <dt>Organisation</dt><dd>{data.organizationName}</dd>
          <dt>Your role</dt><dd>{roleLabel(data.role)}</dd>
          <dt>Email</dt><dd>{data.email}</dd>
          <dt>Expires</dt><dd><time dateTime={data.expiresAt} title={formatDate(data.expiresAt)}>{relativeTime(data.expiresAt)}</time></dd>
        </dl>
      ) : null}
      {data && (
        <>
          {error && <AuthNotice tone="danger">{error}</AuthNotice>}
          <form className="hra-auth-form" onSubmit={event => void submit(event)} noValidate>
            <input type="email" autoComplete="username" value={data.email} readOnly hidden />
            <div className="hra-auth-row">
              <Field label="First name" error={errors.firstName}>
                <Input autoComplete="given-name" maxLength={100} controlSize="lg" value={firstName} onChange={e => setFirstName(e.target.value)} disabled={busy} autoFocus />
              </Field>
              <Field label="Last name" error={errors.lastName}>
                <Input autoComplete="family-name" maxLength={100} controlSize="lg" value={lastName} onChange={e => setLastName(e.target.value)} disabled={busy} />
              </Field>
            </div>
            <NewPasswordField label="Password" value={password} onChange={setPassword} error={errors.password} disabled={busy} />
            <Button type="submit" variant="primary" size="lg" block loading={busy}>{busy ? 'Joining…' : 'Join workspace'}</Button>
          </form>
        </>
      )}
    </AuthLayout>
  )
}
