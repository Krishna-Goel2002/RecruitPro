import { FormEvent, useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { authApi, ssoStartUrl } from '../../api/auth'
import { errorMessage, isApiError } from '../../api/client'
import { qk } from '../../api/queryKeys'
import { AuthResponse, MfaChallenge, MfaEnrollment, isMfaChallenge } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { ArrowIcon, LockIcon, MailIcon } from '../../components/Icons'
import { usePageTitle } from '../../lib/hooks'
import { AuthError, AuthInput, AuthLayout, AuthSuccess } from './AuthLayout'

/** `?ssoError=<code>` from the SSO callback (WP-PLAT notes 1.2). Unknown codes get a generic message. */
const SSO_ERROR_TEXT: Record<string, string> = {
  invalid_state: 'The single sign-on attempt expired or was already used. Please try again.',
  cancelled: 'Single sign-on was cancelled.',
  provider_error: 'Your identity provider reported a problem. Please try again.',
  not_configured: 'This single sign-on provider is not set up for RecruitPro.',
  token_exchange_failed: 'Single sign-on could not be completed. Please try again.',
  invalid_token: 'Your identity provider’s answer could not be verified. Please try again.',
  email_not_verified: 'Your identity provider did not confirm your email address.',
  domain_not_allowed: 'Your organisation does not allow single sign-on for this email domain.',
  no_account: 'There is no RecruitPro staff account for this email address. Ask your administrator for an invitation.',
  account_disabled: 'This account is disabled. Contact your administrator.',
  account_locked: 'This account is locked for a few minutes after too many attempts.',
}

export function ssoErrorMessage(code: string): string {
  return SSO_ERROR_TEXT[code.trim().toLowerCase()] ?? 'Single sign-on did not work. Please try again or sign in with your password.'
}

type Step =
  | { kind: 'password' }
  | { kind: 'mfa'; challenge: MfaChallenge }
  | { kind: 'enroll'; challenge: MfaChallenge; enrollment: MfaEnrollment | null }
  | { kind: 'codes'; recoveryCodes: string[]; session: AuthResponse }

const CODE_PATTERN = /^\d{6}$/

export function LoginPage() {
  usePageTitle('Sign in')
  const { acceptSession, endedReason } = useAuth()
  const [searchParams, setSearchParams] = useSearchParams()
  const [ssoError] = useState(() => {
    const code = searchParams.get('ssoError')
    return code ? ssoErrorMessage(code) : null
  })
  const [step, setStep] = useState<Step>({ kind: 'password' })
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Show the SSO error once, then clean the URL so a reload does not repeat it.
  useEffect(() => {
    if (!searchParams.has('ssoError')) return
    const next = new URLSearchParams(searchParams)
    next.delete('ssoError')
    setSearchParams(next, { replace: true })
  }, [searchParams, setSearchParams])

  async function adopt(auth: AuthResponse) {
    await acceptSession(auth)
    // PublicOnly redirects to the page the user came from, or their home screen.
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (!email.trim()) return setError('Enter your work email address.')
    if (!password) return setError('Enter your password.')
    setBusy(true)
    try {
      const result = await authApi.login({ email: email.trim(), password })
      if (isMfaChallenge(result)) {
        setPassword('')
        setStep(result.mfaEnrollmentRequired ? { kind: 'enroll', challenge: result, enrollment: null } : { kind: 'mfa', challenge: result })
        return
      }
      await adopt(result)
    } catch (cause) {
      setError(isApiError(cause, 401) ? 'Email or password is incorrect, or the account is locked for a few minutes.' : errorMessage(cause))
      setPassword('')
    } finally {
      setBusy(false)
    }
  }

  function restart(message?: string) {
    setStep({ kind: 'password' })
    setError(message ?? null)
  }

  if (step.kind === 'mfa') {
    return <MfaStep challenge={step.challenge} onSession={adopt} onRestart={restart} />
  }
  if (step.kind === 'enroll') {
    return (
      <EnrollStep
        challenge={step.challenge}
        onConfirmed={(recoveryCodes, session) => setStep({ kind: 'codes', recoveryCodes, session })}
        onRestart={restart}
      />
    )
  }
  if (step.kind === 'codes') {
    return <RecoveryCodesStep codes={step.recoveryCodes} onContinue={() => adopt(step.session)} />
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
      {endedReason && !error && !ssoError && <p className="auth-info" role="status">{endedReason}</p>}
      {ssoError && !error && <AuthError message={ssoError} />}
      <form className="sign-in-form" onSubmit={event => void submit(event)} noValidate>
        <AuthInput label="Work email" icon={<MailIcon className="input-icon" />} type="email" autoComplete="username" inputMode="email" maxLength={255} placeholder="you@company.com" value={email} onChange={event => setEmail(event.target.value)} disabled={busy} required />
        <AuthInput label="Password" icon={<LockIcon className="input-icon" />} type="password" revealable autoComplete="current-password" maxLength={72} placeholder="Enter your password" value={password} onChange={event => setPassword(event.target.value)} disabled={busy} required />
        <AuthError message={error} />
        <button className="primary-button" type="submit" disabled={busy}>
          {busy ? <><span className="spinner" aria-hidden="true" />Signing in…</> : <>Sign in to HR workspace <ArrowIcon /></>}
        </button>
      </form>
      <SsoButtons />
      <div className="form-security-note"><LockIcon className="lock-icon" /><p>Authorised staff only. The server checks your role for every action.</p></div>
    </AuthLayout>
  )
}

/** "Continue with …" for each configured OIDC provider; nothing when SSO is not set up. */
function SsoButtons() {
  const providers = useQuery({ queryKey: qk.ssoProviders, queryFn: ({ signal }) => authApi.ssoProviders(signal), staleTime: 5 * 60_000, retry: false })
  if (!providers.data || providers.data.length === 0) return null
  return (
    <div className="sso-options" aria-label="Single sign-on">
      <p className="sso-divider"><span>or</span></p>
      {providers.data.map(provider => (
        <a key={provider.id} className="secondary-button sso-button" href={ssoStartUrl(provider.id)}>
          Continue with {provider.name}
        </a>
      ))}
    </div>
  )
}

function MfaStep({ challenge, onSession, onRestart }: {
  challenge: MfaChallenge
  onSession: (auth: AuthResponse) => Promise<void>
  onRestart: (message?: string) => void
}) {
  const [useRecovery, setUseRecovery] = useState(false)
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const expiresAt = useRef(Date.now() + challenge.expiresInSeconds * 1000)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    const value = code.trim()
    if (Date.now() > expiresAt.current) return onRestart('The sign-in step expired. Please enter your password again.')
    if (!useRecovery && !CODE_PATTERN.test(value)) return setError('Enter the 6-digit code from your authenticator app.')
    if (useRecovery && value.replace(/[\s-]/g, '').length < 10) return setError('Enter one of your recovery codes, for example abcde-fgh23.')
    setBusy(true)
    try {
      const auth = await authApi.verifyMfa(useRecovery ? { mfaToken: challenge.mfaToken, recoveryCode: value } : { mfaToken: challenge.mfaToken, code: value })
      await onSession(auth)
    } catch (cause) {
      const message = errorMessage(cause)
      if (isApiError(cause, 401) && /expired|sign in again/i.test(message)) return onRestart('The sign-in step expired. Please enter your password again.')
      setError(isApiError(cause, 401) ? `${message}. Check the code and try again.`.replace('..', '.') : message)
      setCode('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthLayout
      eyebrow="TWO-STEP VERIFICATION"
      title="Enter your code"
      intro={useRecovery ? 'Enter one of the recovery codes you saved when you set up two-step verification. Each code works once.' : 'Open your authenticator app and enter the 6-digit code for RecruitPro.'}
      footer={<button type="button" className="secondary-button" onClick={() => onRestart()}>Back to sign in</button>}
    >
      <form className="sign-in-form" onSubmit={event => void submit(event)} noValidate>
        {useRecovery ? (
          <AuthInput key="recovery" label="Recovery code" type="text" autoComplete="off" autoCapitalize="none" spellCheck={false} maxLength={20} value={code} onChange={event => setCode(event.target.value)} disabled={busy} required autoFocus />
        ) : (
          <AuthInput key="totp" label="6-digit code" type="text" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]*" maxLength={6} value={code} onChange={event => setCode(event.target.value.replace(/\D/g, ''))} disabled={busy} required autoFocus />
        )}
        <AuthError message={error} />
        <button className="primary-button" type="submit" disabled={busy}>
          {busy ? <><span className="spinner" aria-hidden="true" />Checking…</> : <>Verify and sign in <ArrowIcon /></>}
        </button>
        <button type="button" className="secondary-button" onClick={() => { setUseRecovery(value => !value); setCode(''); setError(null) }} disabled={busy}>
          {useRecovery ? 'Use my authenticator app instead' : 'Use a recovery code'}
        </button>
      </form>
    </AuthLayout>
  )
}

function EnrollStep({ challenge, onConfirmed, onRestart }: {
  challenge: MfaChallenge
  onConfirmed: (recoveryCodes: string[], session: AuthResponse) => void
  onRestart: (message?: string) => void
}) {
  const [enrollment, setEnrollment] = useState<MfaEnrollment | null>(null)
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function start() {
    setError(null)
    setBusy(true)
    try {
      setEnrollment(await authApi.enrollWithToken(challenge.mfaToken))
    } catch (cause) {
      if (isApiError(cause, 401)) return onRestart('The sign-in step expired. Please enter your password again.')
      setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  async function confirm(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (!CODE_PATTERN.test(code.trim())) return setError('Enter the 6-digit code your authenticator app shows.')
    setBusy(true)
    try {
      const result = await authApi.confirmWithToken(challenge.mfaToken, code.trim())
      if (!result.session) return onRestart('Two-step verification is set up. Please sign in again.')
      onConfirmed(result.recoveryCodes, result.session)
    } catch (cause) {
      if (isApiError(cause, 401)) return onRestart('The sign-in step expired. Please enter your password again.')
      setError(isApiError(cause, 400) ? 'That code did not match. Check the time on your phone and try the newest code.' : errorMessage(cause))
      setCode('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthLayout
      eyebrow="TWO-STEP VERIFICATION REQUIRED"
      title="Set up two-step verification"
      intro="Your organisation requires two-step verification for staff accounts. Set it up now to finish signing in."
      footer={<button type="button" className="secondary-button" onClick={() => onRestart()}>Back to sign in</button>}
    >
      {!enrollment ? (
        <div className="sign-in-form">
          <p className="auth-info">You need an authenticator app on your phone, for example Google Authenticator, Microsoft Authenticator or 1Password.</p>
          <AuthError message={error} />
          <button type="button" className="primary-button" onClick={() => void start()} disabled={busy}>
            {busy ? <><span className="spinner" aria-hidden="true" />Preparing…</> : <>Start set-up <ArrowIcon /></>}
          </button>
        </div>
      ) : (
        <form className="sign-in-form" onSubmit={event => void confirm(event)} noValidate>
          <MfaSecret enrollment={enrollment} />
          <AuthInput label="6-digit code from the app" type="text" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]*" maxLength={6} value={code} onChange={event => setCode(event.target.value.replace(/\D/g, ''))} disabled={busy} required />
          <AuthError message={error} />
          <button className="primary-button" type="submit" disabled={busy}>
            {busy ? <><span className="spinner" aria-hidden="true" />Checking…</> : <>Confirm and sign in <ArrowIcon /></>}
          </button>
        </form>
      )}
    </AuthLayout>
  )
}

/** The secret and otpauth URI as selectable text (no QR library in the bundle). Shared with the account page. */
export function MfaSecret({ enrollment }: { enrollment: MfaEnrollment }) {
  const grouped = enrollment.secret.replace(/(.{4})/g, '$1 ').trim()
  return (
    <div className="mfa-secret">
      <p>In your authenticator app, add an account and choose to enter a setup key. Use this key (time-based):</p>
      <code className="mfa-key" aria-label={`Setup key ${enrollment.secret.split('').join(' ')}`}>{grouped}</code>
      <details>
        <summary>Show the setup link instead</summary>
        <p className="field-hint">Some apps and password managers accept this link directly.</p>
        <code className="mfa-uri">{enrollment.otpauthUri}</code>
      </details>
    </div>
  )
}

/** Recovery codes are shown exactly once; the session is adopted only after the user says they saved them. */
export function RecoveryCodesList({ codes }: { codes: string[] }) {
  return (
    <ol className="recovery-codes" aria-label="Recovery codes">
      {codes.map(code => <li key={code}><code>{code}</code></li>)}
    </ol>
  )
}

function RecoveryCodesStep({ codes, onContinue }: { codes: string[]; onContinue: () => Promise<void> }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  return (
    <AuthLayout eyebrow="TWO-STEP VERIFICATION" title="Save your recovery codes" intro="If you lose your phone, each of these codes lets you sign in once. They will not be shown again.">
      <AuthSuccess>Two-step verification is on.</AuthSuccess>
      <RecoveryCodesList codes={codes} />
      <AuthError message={error} />
      <button
        type="button"
        className="primary-button"
        disabled={busy}
        onClick={() => {
          setBusy(true)
          onContinue().catch(cause => { setError(errorMessage(cause)); setBusy(false) })
        }}
      >
        I have saved them, continue <ArrowIcon />
      </button>
    </AuthLayout>
  )
}
