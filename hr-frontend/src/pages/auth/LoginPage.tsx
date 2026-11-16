import { FormEvent, KeyboardEvent, useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { authApi, ssoStartUrl } from '../../api/auth'
import { errorMessage, isApiError } from '../../api/client'
import { qk } from '../../api/queryKeys'
import { AuthResponse, MfaChallenge, isMfaChallenge } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import brand from '../../design/brand/active'
import { Button, Field, IconButton, Input, buttonProps } from '../../design/components'
import { ArrowRight, Eye, EyeOff, Icon, TriangleAlert } from '../../design/icons'
import { AuthLayout, AuthNotice } from './AuthLayout'
import { EnrollStep, MfaStep, RecoveryCodesStep } from './MfaSteps'

export { MfaSecret, RecoveryCodesList } from './MfaSteps'

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
