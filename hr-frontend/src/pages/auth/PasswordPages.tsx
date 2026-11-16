import { FormEvent, useRef, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { authApi } from '../../api/auth'
import { errorMessage } from '../../api/client'
import { PasswordChecklist, passwordError } from '../../components/PasswordChecklist'
import { Button, Field, IconButton, Input, buttonProps } from '../../design/components'
import { Eye, EyeOff } from '../../design/icons'
import { AuthLayout, AuthNotice } from './AuthLayout'

/** /forgot-password: always answers the same way, so it never reveals whether an account exists. */
export function ForgotPasswordPage() {
  const location = useLocation()
  const [email, setEmail] = useState(() => (location.state as { email?: string } | null)?.email ?? '')
  const [busy, setBusy] = useState(false)
  const [sent, setSent] = useState(false)
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (!/^\S+@\S+\.\S+$/.test(email.trim())) return setFieldError('Enter the email address of your account.')
    setFieldError(null)
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
    <AuthLayout
      stepKey={sent ? 'sent' : 'form'}
      title={sent ? 'Check your inbox' : 'Reset your password'}
      intro={sent
        ? <>If an account exists for <strong>{email.trim()}</strong>, a reset link is on its way. It works once and expires after an hour. Check spam if it does not arrive.</>
        : 'Enter your work email and we will send a link to choose a new password.'}
      footer={<Link to="/login">Back to sign in</Link>}
    >
      {sent ? (
        <div className="hra-auth-actions">
          <Link {...buttonProps('secondary', 'lg')} to="/login" data-block="">Back to sign in</Link>
          <button type="button" className="hra-link-btn" onClick={() => setSent(false)}>Use a different email</button>
        </div>
      ) : (
        <>
          {error && <AuthNotice tone="danger">{error}</AuthNotice>}
          <form className="hra-auth-form" onSubmit={event => void submit(event)} noValidate>
            <Field label="Work email" error={fieldError}>
              <Input type="email" autoComplete="email" inputMode="email" maxLength={255} controlSize="lg" value={email} onChange={event => setEmail(event.target.value)} disabled={busy} autoFocus />
            </Field>
            <Button type="submit" variant="primary" size="lg" block loading={busy}>{busy ? 'Sending…' : 'Send reset link'}</Button>
          </form>
        </>
      )}
    </AuthLayout>
  )
}

/** A new-password field with show/hide and the live rule checklist. */
export function NewPasswordField({ label = 'New password', value, onChange, error, disabled, autoFocus }: {
  label?: string
  value: string
  onChange: (value: string) => void
  error?: string | null
  disabled?: boolean
  autoFocus?: boolean
}) {
  const [visible, setVisible] = useState(false)
  return (
    <div>
      <Field label={label} error={error}>
        <Input
          type={visible ? 'text' : 'password'}
          autoComplete="new-password"
          maxLength={200}
          controlSize="lg"
          value={value}
          onChange={event => onChange(event.target.value)}
          disabled={disabled}
          autoFocus={autoFocus}
          aria-describedby="hra-pw-rules"
          trailing={<IconButton label={visible ? 'Hide password' : 'Show password'} icon={visible ? EyeOff : Eye} size="sm" aria-pressed={visible} onClick={() => setVisible(v => !v)} />}
        />
      </Field>
      <PasswordChecklist password={value} id="hra-pw-rules" />
    </div>
  )
}

/** /reset-password?token=… */
export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<{ password?: string | null; confirm?: string | null }>({})
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const confirmRef = useRef<HTMLInputElement>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    const next = { password: passwordError(password), confirm: confirm !== password ? 'The two passwords do not match.' : null }
    setErrors(next)
    if (next.password || next.confirm) {
      if (!next.password) confirmRef.current?.focus()
      return
    }
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
    <AuthLayout
      stepKey={done ? 'done' : 'form'}
      title={done ? 'Password changed' : 'Choose a new password'}
      intro={done ? 'Your other sessions were signed out. Sign in with your new password.' : 'Other sessions are signed out when your password changes.'}
      footer={<Link to="/login">Back to sign in</Link>}
    >
      {!token ? (
        <>
          <AuthNotice tone="danger" title="This link is incomplete">Open the link from your email again, or request a new one.</AuthNotice>
          <div className="hra-auth-actions"><Link {...buttonProps('secondary', 'lg')} to="/forgot-password">Request a new link</Link></div>
        </>
      ) : done ? (
        <div className="hra-auth-actions"><Link {...buttonProps('primary', 'lg')} data-block="" to="/login">Sign in</Link></div>
      ) : (
        <>
          {error && (
            <AuthNotice tone="danger">
              {error} <Link to="/forgot-password">Request a new link</Link>
            </AuthNotice>
          )}
          <form className="hra-auth-form" onSubmit={event => void submit(event)} noValidate>
            <NewPasswordField value={password} onChange={setPassword} error={errors.password} disabled={busy} autoFocus />
            <Field label="Confirm new password" error={errors.confirm}>
              <Input ref={confirmRef} type="password" autoComplete="new-password" maxLength={200} controlSize="lg" value={confirm} onChange={event => setConfirm(event.target.value)} disabled={busy} />
            </Field>
            <Button type="submit" variant="primary" size="lg" block loading={busy}>{busy ? 'Saving…' : 'Save new password'}</Button>
          </form>
        </>
      )}
    </AuthLayout>
  )
}
