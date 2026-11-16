import { useId, useRef, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { authApi } from '../../api/endpoints'
import { useAuth } from '../../auth/AuthProvider'
import { FormError } from '../../components/Feedback'
import { PasswordChecklist, PasswordInput, focusFirstInvalid } from '../../components/Form'
import { Button, Field, buttonProps } from '../../design/components'
import { CircleAlert, CircleCheck, Icon } from '../../design/icons'
import { PASSWORD_POLICY_TEXT, isPasswordValid } from '../../lib/password'
import { AuthHeading } from './AuthLayout'

export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token')?.trim() ?? ''
  const { status, endSession } = useAuth()
  const checklistId = useId()
  const formRef = useRef<HTMLFormElement>(null)
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<{ password?: string; confirm?: string }>({})

  const reset = useMutation({
    mutationFn: () => authApi.resetPassword({ token, newPassword: password }),
    onSuccess: () => {
      // The server signs out every session after a reset; drop ours too.
      if (status === 'authenticated') endSession('password-reset')
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: typeof errors = {}
    if (!isPasswordValid(password)) next.password = PASSWORD_POLICY_TEXT
    if (password !== confirm) next.confirm = 'The passwords do not match.'
    setErrors(next)
    if (next.password || next.confirm) return focusFirstInvalid(formRef.current)
    reset.mutate()
  }

  if (!token) {
    return (
      <div className="auth-state">
        <span className="auth-state-icon" data-tone="danger">
          <Icon icon={CircleAlert} size={20} />
        </span>
        <AuthHeading title="This link is incomplete">
          The reset link is missing its token. Open the link from the email again, or request a new one.
        </AuthHeading>
        <Link {...buttonProps('primary', 'lg')} to="/forgot-password">
          Request a new link
        </Link>
      </div>
    )
  }

  if (reset.isSuccess) {
    return (
      <div className="auth-state">
        <span className="auth-state-icon">
          <Icon icon={CircleCheck} size={20} />
        </span>
        <AuthHeading title="Password changed">Every session was signed out. Sign in with your new password.</AuthHeading>
        <Link {...buttonProps('primary', 'lg')} to="/login">
          Go to sign in
        </Link>
      </div>
    )
  }

  return (
    <>
      <AuthHeading title="Choose a new password">Signing in elsewhere ends when your password changes.</AuthHeading>
      <form ref={formRef} onSubmit={submit} noValidate>
        <div className="auth-fields">
          <div>
            <Field label="New password" error={errors.password}>
              <PasswordInput controlSize="lg" value={password} onChange={setPassword} autoComplete="new-password" aria-describedby={checklistId} autoFocus />
            </Field>
            <PasswordChecklist password={password} id={checklistId} />
          </div>
          <Field label="Confirm new password" error={errors.confirm}>
            <PasswordInput controlSize="lg" value={confirm} onChange={setConfirm} autoComplete="new-password" />
          </Field>
          <FormError error={reset.error} />
          <div className="auth-actions">
            <Button type="submit" variant="primary" size="lg" block loading={reset.isPending}>
              Set new password
            </Button>
          </div>
        </div>
      </form>
      <p className="auth-switch">
        Link not working? <Link to="/forgot-password">Request a new one</Link>
      </p>
    </>
  )
}
