import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { authApi } from '../../api/auth'
import { errorMessage, isApiError, isLockout, waitText } from '../../api/client'
import { AuthResponse, MfaChallenge, MfaEnrollment } from '../../api/types'
import { OtpInput } from '../../components/OtpInput'
import { RecoveryCodes } from '../../components/RecoveryCodes'
import brand from '../../design/brand/active'
import { Button, CodeField, Field, Input, Skeleton, Spinner, Stepper } from '../../design/components'
import { Clock, Icon } from '../../design/icons'
import { AuthLayout, AuthNotice } from './AuthLayout'

const QrCode = lazy(() => import('../../components/QrCode'))

/**
 * The second sign-in step, shared by /login and /accept-invite (an invitation into an organisation that requires
 * two-step verification answers with the same challenge as a login, R1-M4): code or recovery code, or the forced
 * enrolment (scan → confirm → save codes). `onRestart(message)` leaves the step (expired, locked, or "Back").
 */

const CODE_PATTERN = /^\d{6}$/
export const EXPIRED = 'The sign-in step expired. Enter your password again.'

/** The lockout message, with the wait the server announced in Retry-After when there is one. */
export function lockedMessage(cause?: unknown): string {
  const wait = waitText(cause)
  return wait
    ? `Too many wrong codes. For your security sign-in is paused for ${wait}; then enter your password again.`
    : 'Too many wrong codes. For your security sign-in is paused for a few minutes; then enter your password again.'
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

export function MfaStep({ challenge, email, onSession, onRestart, backLabel = 'Back to sign in' }: {
  challenge: MfaChallenge
  email: string
  onSession: (auth: AuthResponse) => Promise<void>
  onRestart: (message?: string) => void
  backLabel?: string
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
      if (isLockout(cause)) return onRestart(lockedMessage(cause))
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
      footer={<button type="button" className="hra-link-btn" onClick={() => onRestart()}>{backLabel}</button>}
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

export function EnrollStep({ challenge, onConfirmed, onRestart, backLabel = 'Back to sign in' }: {
  challenge: MfaChallenge
  onConfirmed: (recoveryCodes: string[], session: AuthResponse) => void
  onRestart: (message?: string) => void
  backLabel?: string
}) {
  const [enrollment, setEnrollment] = useState<MfaEnrollment | null>(null)
  const [startError, setStartError] = useState<string | null>(null)
  const [startRetryable, setStartRetryable] = useState(true)
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
      if (isLockout(cause)) return onRestart(lockedMessage(cause))
      if (isApiError(cause, 401)) return onRestart(EXPIRED)
      setStartError(errorMessage(cause))
      setStartRetryable(!(isApiError(cause) && cause.code === 'MFA_NOT_CONFIGURED'))
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
      if (isLockout(cause)) return onRestart(lockedMessage(cause))
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
      footer={<button type="button" className="hra-link-btn" onClick={() => onRestart()}>{backLabel}</button>}
    >
      <Stepper className="hra-enrol-stepper" steps={['Scan', 'Confirm', 'Save codes']} current={code.length > 0 ? 1 : 0} label="Set-up progress" />
      {startError ? (
        <>
          <AuthNotice tone="danger" title="Set-up could not start">{startError}</AuthNotice>
          <div className="hra-auth-actions">{startRetryable ? <Button variant="primary" onClick={() => void start()}>Try again</Button> : <Button variant="secondary" onClick={() => onRestart()}>{backLabel}</Button>}</div>
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

export function RecoveryCodesStep({ codes, email, onContinue }: { codes: string[]; email: string; onContinue: () => Promise<void> }) {
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
