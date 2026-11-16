import { FormEvent, KeyboardEvent, lazy, Suspense, useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { authApi, ssoStartUrl } from '../../api/auth'
import { errorMessage, isApiError } from '../../api/client'
import { qk } from '../../api/queryKeys'
import { AuthResponse, MfaChallenge, MfaEnrollment, isMfaChallenge } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { OtpInput } from '../../components/OtpInput'
import { RecoveryCodes } from '../../components/RecoveryCodes'
import brand from '../../design/brand/active'
import { Button, CodeField, Field, IconButton, Input, Skeleton, Spinner, Stepper, buttonProps } from '../../design/components'
import { ArrowRight, Clock, Eye, EyeOff, Icon, TriangleAlert } from '../../design/icons'
import { AuthLayout, AuthNotice } from './AuthLayout'

const QrCode = lazy(() => import('../../components/QrCode'))

/** `?ssoError=<code>` from the SSO callback (WP-PLAT notes 1.2). Unknown codes get a generic message. */
const SSO_ERROR_TEXT: Record<string, string> = {
  invalid_state: 'The single sign-on attempt expired or was already used. Please try again.',
  cancelled: 'Single sign-on was cancelled.',
  provider_error: 'Your identity provider reported a problem. Please try again.',
  not_configured: `This single sign-on provider is not set up for ${brand.name}.`,
  token_exchange_failed: 'Single sign-on could not be completed. Please try again.',
  invalid_token: 'Your identity provider’s answer could not be verified. Please try again.',
  email_not_verified: 'Your identity provider did not confirm your email address.',
  domain_not_allowed: 'Your organisation does not allow single sign-on for this email domain.',
  no_account: `There is no ${brand.name} staff account for this email address. Ask your administrator for an invitation.`,
  account_disabled: 'This account is disabled. Contact your administrator.',
  account_locked: 'This account is locked for a few minutes after too many attempts.',
}

export function ssoErrorMessage(code: string): string {
  return SSO_ERROR_TEXT[code.trim().toLowerCase()] ?? 'Single sign-on did not work. Please try again or sign in with your password.'
}

type Step =
  | { kind: 'password' }
  | { kind: 'mfa'; challenge: MfaChallenge }
  | { kind: 'enroll'; challenge: MfaChallenge }
  | { kind: 'codes'; recoveryCodes: string[]; session: AuthResponse }

const CODE_PATTERN = /^\d{6}$/
const EXPIRED = 'The sign-in step expired. Enter your password again.'

/**
 * /login: password → (MFA code | recovery code) → or forced enrolment (scan → confirm → save codes). SSO providers sit
 * directly under the primary button. Every step keeps the typed email when it sends the user back.
 */
export function LoginPage() {
  const { acceptSession, endedReason } = useAuth()
  const [searchParams, setSearchParams] = useSearchParams()
  const [ssoError] = useState(() => {
    const code = searchParams.get('ssoError')
    return code ? ssoErrorMessage(code) : null
  })
  const [step, setStep] = useState<Step>({ kind: 'password' })
  const [email, setEmail] = useState('')
  const [notice, setNotice] = useState<string | null>(null)

  // Read the SSO error once, then clean the URL so a reload does not repeat it.
  useEffect(() => {
    if (!searchParams.has('ssoError')) return
    const next = new URLSearchParams(searchParams)
    next.delete('ssoError')
    setSearchParams(next, { replace: true })
  }, [searchParams, setSearchParams])

  async function adopt(auth: AuthResponse) {
    await acceptSession(auth)
    // PublicOnly sends the user to the page they came from, or to their home screen.
  }

  function restart(message?: string) {
    setStep({ kind: 'password' })
    setNotice(message ?? null)
  }

  if (step.kind === 'mfa') return <MfaStep challenge={step.challenge} email={email} onSession={adopt} onRestart={restart} />
  if (step.kind === 'enroll') {
    return <EnrollStep challenge={step.challenge} onConfirmed={(recoveryCodes, session) => setStep({ kind: 'codes', recoveryCodes, session })} onRestart={restart} />
  }
  if (step.kind === 'codes') return <RecoveryCodesStep codes={step.recoveryCodes} email={email} onContinue={() => adopt(step.session)} />

  return (
    <PasswordStep
      email={email}
      onEmail={setEmail}
      banner={notice ?? (ssoError ? null : endedReason)}
      ssoError={ssoError}
      onChallenge={challenge => {
        setNotice(null)
        setStep(challenge.mfaEnrollmentRequired ? { kind: 'enroll', challenge } : { kind: 'mfa', challenge })
      }}
      onSession={adopt}
    />
  )
}

function PasswordStep({ email, onEmail, banner, ssoError, onChallenge, onSession }: {
  email: string
  onEmail: (value: string) => void
  banner: string | null
  ssoError: string | null
  onChallenge: (challenge: MfaChallenge) => void
  onSession: (auth: AuthResponse) => Promise<void>
}) {
  const [password, setPassword] = useState('')
  const [visible, setVisible] = useState(false)
  const [capsLock, setCapsLock] = useState(false)
  const [busy, setBusy] = useState(false)
  const [errors, setErrors] = useState<{ email?: string; password?: string }>({})
  const [error, setError] = useState<string | null>(null)
  const emailRef = useRef<HTMLInputElement>(null)
  const passwordRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    // Returning from a later step: the email is kept, so start in the password field.
    ;(email ? passwordRef.current : emailRef.current)?.focus()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    const next: typeof errors = {}
    if (!email.trim()) next.email = 'Enter your work email address.'
    else if (!/^\S+@\S+\.\S+$/.test(email.trim())) next.email = 'Enter an email address like name@company.com.'
    if (!password) next.password = 'Enter your password.'
    setErrors(next)
    if (next.email) return emailRef.current?.focus()
    if (next.password) return passwordRef.current?.focus()
    setBusy(true)
    try {
      const result = await authApi.login({ email: email.trim(), password })
      if (isMfaChallenge(result)) {
        setPassword('')
        onChallenge(result)
        return
      }
      await onSession(result)
    } catch (cause) {
      setError(
        isApiError(cause, 401)
          ? 'That email and password do not match an account, or the account is locked for a few minutes after too many attempts.'
          : isApiError(cause, 0)
            ? 'We could not reach the server. Your details are kept; try again in a moment.'
            : errorMessage(cause),
      )
      setPassword('')
      passwordRef.current?.focus()
    } finally {
      setBusy(false)
    }
  }

  const onKey = (event: KeyboardEvent<HTMLInputElement>) => setCapsLock(event.getModifierState?.('CapsLock') ?? false)

  return (
    <AuthLayout
      title="Sign in"
      intro={`Use your ${brand.name} work account.`}
      footer={<span>New organisation? <Link to="/signup">Create a workspace</Link></span>}
    >
      {error ? (
        <AuthNotice tone="danger">{error}</AuthNotice>
      ) : ssoError ? (
        <AuthNotice tone="danger" title="Single sign-on did not complete">{ssoError}</AuthNotice>
      ) : banner ? (
        <AuthNotice tone="info">{banner}</AuthNotice>
      ) : null}
      <form className="hra-auth-form" onSubmit={event => void submit(event)} noValidate>
        <Field label="Work email" error={errors.email}>
          <Input
            ref={emailRef}
            type="email"
            autoComplete="username"
            inputMode="email"
            maxLength={255}
            placeholder="name@company.com"
            value={email}
            controlSize="lg"
            onChange={event => {
              onEmail(event.target.value)
              if (errors.email) setErrors(current => ({ ...current, email: undefined }))
            }}
            disabled={busy}
          />
        </Field>
        <div>
          <Field label="Password" error={errors.password}>
            <Input
              ref={passwordRef}
              type={visible ? 'text' : 'password'}
              autoComplete="current-password"
              maxLength={200}
              value={password}
              controlSize="lg"
              onChange={event => {
                setPassword(event.target.value)
                if (errors.password) setErrors(current => ({ ...current, password: undefined }))
              }}
              onKeyDown={onKey}
              onKeyUp={onKey}
              onBlur={() => setCapsLock(false)}
              disabled={busy}
              trailing={
                <IconButton
                  label={visible ? 'Hide password' : 'Show password'}
                  icon={visible ? EyeOff : Eye}
                  size="sm"
                  aria-pressed={visible}
                  onClick={() => setVisible(value => !value)}
                />
              }
            />
          </Field>
          {capsLock && (
            <p className="hra-capslock" role="status">
              <Icon icon={TriangleAlert} size={14} /> Caps Lock is on
            </p>
          )}
          <p className="hra-forgot">
            <Link className="hra-inline-link" to="/forgot-password" state={{ email }}>Forgot your password?</Link>
          </p>
        </div>
        <Button type="submit" variant="primary" size="lg" block loading={busy} trailingIcon={ArrowRight}>
          {busy ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>
      <SsoButtons />
    </AuthLayout>
  )
}

/** "Continue with …" for each configured OIDC provider; nothing when SSO is not set up. */
function SsoButtons() {
  const providers = useQuery({ queryKey: qk.ssoProviders, queryFn: ({ signal }) => authApi.ssoProviders(signal), staleTime: 5 * 60_000, retry: false })
  if (!providers.data || providers.data.length === 0) return null
  return (
    <div aria-label="Single sign-on" role="group">
      <p className="hra-or">or</p>
      <div className="hra-sso">
        {providers.data.map(provider => (
          <a key={provider.id} {...buttonProps('secondary', 'lg')} data-block="" href={ssoStartUrl(provider.id)}>
            <span className="hra-sso-mark" aria-hidden="true">{provider.name.trim().charAt(0).toUpperCase()}</span>
            <span className="ui-btn-label">Continue with {provider.name}</span>
          </a>
        ))}
      </div>
    </div>
  )
}

/** mm:ss until the MFA token expires; null once it has. */
function useCountdown(expiresAt: number): number {
  const [left, setLeft] = useState(() => Math.max(0, expiresAt - Date.now()))
  useEffect(() => {
    const timer = window.setInterval(() => setLeft(Math.max(0, expiresAt - Date.now())), 1000)
    return () => window.clearInterval(timer)
  }, [expiresAt])
  return left
}

function formatLeft(ms: number) {
  const s = Math.ceil(ms / 1000)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

function MfaStep({ challenge, email, onSession, onRestart }: {
  challenge: MfaChallenge
  email: string
  onSession: (auth: AuthResponse) => Promise<void>
  onRestart: (message?: string) => void
}) {
  const [useRecovery, setUseRecovery] = useState(false)
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [shake, setShake] = useState(0)
  const [expiresAt] = useState(() => Date.now() + challenge.expiresInSeconds * 1000)
  const left = useCountdown(expiresAt)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => { inputRef.current?.focus() }, [useRecovery])

  async function verify(value: string) {
    setError(null)
    const trimmed = value.trim()
    if (Date.now() > expiresAt) return onRestart(EXPIRED)
    if (!useRecovery && !CODE_PATTERN.test(trimmed)) return setError('Enter the 6-digit code from your authenticator app.')
    if (useRecovery && trimmed.replace(/[\s-]/g, '').length < 10) return setError('Enter one of your recovery codes, for example abcde-fgh23.')
    setBusy(true)
    try {
      const auth = await authApi.verifyMfa(useRecovery ? { mfaToken: challenge.mfaToken, recoveryCode: trimmed } : { mfaToken: challenge.mfaToken, code: trimmed })
      await onSession(auth)
    } catch (cause) {
      const message = errorMessage(cause)
      if (isApiError(cause, 401) && /expired|sign in again/i.test(message)) return onRestart(EXPIRED)
      setError(isApiError(cause, 401) || isApiError(cause, 400)
        ? useRecovery ? 'That recovery code did not work. Check it, or use another one.' : 'That code did not match. Wait for the next code in your app and try again.'
        : message)
      setCode('')
      setShake(n => n + 1)
      inputRef.current?.focus()
    } finally {
      setBusy(false)
    }
  }

  const expired = left <= 0
  return (
    <AuthLayout
      stepKey={useRecovery ? 'recovery' : 'mfa'}
      title={useRecovery ? 'Use a recovery code' : 'Enter your code'}
      documentTitle="Two-step verification"
      intro={useRecovery
        ? 'Enter one of the recovery codes you saved when you set up two-step verification. Each code works once.'
        : <>Open your authenticator app and enter the 6-digit code for <strong>{brand.name}</strong>{email ? <> ({email})</> : null}.</>}
      footer={<button type="button" className="hra-link-btn" onClick={() => onRestart()}>Back to sign in</button>}
    >
      {expired ? (
        <>
          <AuthNotice tone="warning" title="This step expired">For your security the second step is only valid for a few minutes.</AuthNotice>
          <div className="hra-auth-actions">
            <Button variant="primary" size="lg" block onClick={() => onRestart(EXPIRED)}>Enter my password again</Button>
          </div>
        </>
      ) : (
        <form className="hra-auth-form" onSubmit={event => { event.preventDefault(); void verify(code) }} noValidate>
          {useRecovery ? (
            <Field label="Recovery code" error={error} hint="Spaces and dashes are ignored.">
              <Input ref={inputRef} key="recovery" className="mono" autoComplete="off" autoCapitalize="none" spellCheck={false} maxLength={24} value={code} controlSize="lg" onChange={event => setCode(event.target.value)} disabled={busy} />
            </Field>
          ) : (
            <Field label="6-digit code" error={error}>
              <OtpInput
                ref={inputRef}
                value={code}
                onChange={setCode}
                onComplete={value => void verify(value)}
                disabled={busy}
                errorKey={shake}
              />
            </Field>
          )}
          <Button type="submit" variant="primary" size="lg" block loading={busy}>
            {busy ? 'Checking…' : 'Verify and sign in'}
          </Button>
          <div className="hra-mfa-meta">
            <button type="button" className="hra-link-btn" onClick={() => { setUseRecovery(value => !value); setCode(''); setError(null) }} disabled={busy}>
              {useRecovery ? 'Use my authenticator app instead' : 'Use a recovery code instead'}
            </button>
            <span className="hra-mfa-expiry numeric" data-low={left < 60_000 || undefined}>
              <Icon icon={Clock} size={14} />
              <span aria-hidden="true">Expires in {formatLeft(left)}</span>
              <span className="sr-only">This step expires in about {Math.max(1, Math.round(left / 60_000))} minutes</span>
            </span>
          </div>
        </form>
      )}
    </AuthLayout>
  )
}

function EnrollStep({ challenge, onConfirmed, onRestart }: {
  challenge: MfaChallenge
  onConfirmed: (recoveryCodes: string[], session: AuthResponse) => void
  onRestart: (message?: string) => void
}) {
  const [enrollment, setEnrollment] = useState<MfaEnrollment | null>(null)
  const [startError, setStartError] = useState<string | null>(null)
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [shake, setShake] = useState(0)
  const started = useRef(false)

  async function start() {
    setStartError(null)
    try {
      setEnrollment(await authApi.enrollWithToken(challenge.mfaToken))
    } catch (cause) {
      if (isApiError(cause, 401)) return onRestart(EXPIRED)
      setStartError(errorMessage(cause))
    }
  }

  // No "Start set-up" press: the secret is requested as soon as the step opens (once, even under StrictMode).
  useEffect(() => {
    if (started.current) return
    started.current = true
    void start()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function confirm(value: string) {
    setError(null)
    if (!CODE_PATTERN.test(value.trim())) return setError('Enter the 6-digit code your authenticator app shows.')
    setBusy(true)
    try {
      const result = await authApi.confirmWithToken(challenge.mfaToken, value.trim())
      if (!result.session) return onRestart('Two-step verification is set up. Sign in again to continue.')
      onConfirmed(result.recoveryCodes, result.session)
    } catch (cause) {
      if (isApiError(cause, 401)) return onRestart(EXPIRED)
      setError(isApiError(cause, 400) ? 'That code did not match. Check the time on your phone and try the newest code.' : errorMessage(cause))
      setCode('')
      setShake(n => n + 1)
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthLayout
      stepKey="enroll"
      overline="Required by your organisation"
      title="Set up two-step verification"
      intro="Your organisation asks every staff member to confirm sign-ins with an authenticator app. It takes about a minute."
      footer={<button type="button" className="hra-link-btn" onClick={() => onRestart()}>Back to sign in</button>}
    >
      <Stepper className="hra-enrol-stepper" steps={['Scan', 'Confirm', 'Save codes']} current={code.length > 0 ? 1 : 0} label="Set-up progress" />
      {startError ? (
        <>
          <AuthNotice tone="danger" title="Set-up could not start">{startError}</AuthNotice>
          <div className="hra-auth-actions"><Button variant="primary" onClick={() => void start()}>Try again</Button></div>
        </>
      ) : (
        <>
          <div className="hra-enrol-scan">
            <div className="hra-enrol-qr">
              {enrollment ? (
                <Suspense fallback={<Skeleton width={136} height={136} radius={4} />}>
                  <QrCode value={enrollment.otpauthUri} label="QR code for your authenticator app" size={136} />
                </Suspense>
              ) : (
                <Skeleton width={136} height={136} radius={4} />
              )}
            </div>
            <div>
              <div className="hra-enrol-help">
                <ol>
                  <li>Open an authenticator app (Google Authenticator, Microsoft Authenticator, 1Password…).</li>
                  <li>Add an account and scan this code.</li>
                </ol>
              </div>
              {enrollment ? (
                <details className="hra-disclosure">
                  <summary>Can’t scan? Enter a setup key</summary>
                  <MfaSecret enrollment={enrollment} />
                </details>
              ) : (
                <p className="hra-auth-state" role="status"><Spinner size={14} /> Preparing your setup key…</p>
              )}
            </div>
          </div>
          <form className="hra-auth-form" onSubmit={event => { event.preventDefault(); void confirm(code) }} noValidate>
            <Field label="Enter the 6-digit code the app shows" error={error}>
              <OtpInput value={code} onChange={setCode} onComplete={value => void confirm(value)} disabled={busy || !enrollment} errorKey={shake} />
            </Field>
            <Button type="submit" variant="primary" size="lg" block loading={busy}>
              {busy ? 'Checking…' : 'Confirm and continue'}
            </Button>
          </form>
        </>
      )}
    </AuthLayout>
  )
}

/** The setup key (grouped) and the otpauth link, both copyable. Shared with the account security page. */
export function MfaSecret({ enrollment }: { enrollment: MfaEnrollment }) {
  const grouped = enrollment.secret.replace(/(.{4})/g, '$1 ').trim()
  return (
    <div className="stack-6">
      <p className="hra-enrol-help hra-flush">Choose “enter a setup key”, pick time-based, and use this key:</p>
      <CodeField label="Setup key" value={grouped} />
      <CodeField label="Setup link (some apps accept it directly)" value={enrollment.otpauthUri} truncate="middle" />
    </div>
  )
}

/** Recovery codes as a plain ordered list (kept for older callers; new screens use components/RecoveryCodes). */
export function RecoveryCodesList({ codes }: { codes: string[] }) {
  return (
    <ol className="sx-codes-grid" aria-label="Recovery codes">
      {codes.map(code => <li key={code} className="mono">{code}</li>)}
    </ol>
  )
}

function RecoveryCodesStep({ codes, email, onContinue }: { codes: string[]; email: string; onContinue: () => Promise<void> }) {
  const [error, setError] = useState<string | null>(null)
  return (
    <AuthLayout
      stepKey="codes"
      overline="Two-step verification is on"
      title="Save your recovery codes"
      intro="If you lose your phone, each code signs you in once. This is the only time they are shown."
    >
      <Stepper className="hra-enrol-stepper" steps={['Scan', 'Confirm', 'Save codes']} current={2} label="Set-up progress" />
      {error && <AuthNotice tone="danger">{error}</AuthNotice>}
      <div className="hra-auth-form">
        <RecoveryCodes
          codes={codes}
          email={email}
          doneLabel="Continue to workspace"
          onDone={() => { onContinue().catch(cause => setError(errorMessage(cause))) }}
        />
      </div>
    </AuthLayout>
  )
}
