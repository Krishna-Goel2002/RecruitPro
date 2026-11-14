import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { authApi } from '../../api/endpoints'
import { useAuth } from '../../auth/AuthProvider'
import { Field, PasswordChecklist, PasswordInput } from '../../components/Form'
import { FormError } from '../../components/Feedback'
import { PASSWORD_POLICY_TEXT, isPasswordValid } from '../../lib/password'
import { AuthHeading } from './AuthLayout'

export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token')?.trim() ?? ''
  const { status, endSession } = useAuth()
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [formError, setFormError] = useState<string | null>(null)

  const reset = useMutation({
    mutationFn: () => authApi.resetPassword({ token, newPassword: password }),
    onSuccess: () => {
      // The server signs out every session after a reset; drop ours too.
      if (status === 'authenticated') endSession('password-reset')
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!isPasswordValid(password)) {
      setFormError(PASSWORD_POLICY_TEXT)
      return
    }
    if (password !== confirm) {
      setFormError('The passwords do not match.')
      return
    }
    setFormError(null)
    reset.mutate()
  }

  if (!token) {
    return (
      <>
        <AuthHeading title="This link is incomplete" />
        <p className="alert error" role="alert">
          The reset link is missing its token. Open the link from the email again, or request a new one.
        </p>
        <div className="auth-links">
          <Link to="/forgot-password">Request a new reset link</Link>
        </div>
      </>
    )
  }

  if (reset.isSuccess) {
    return (
      <>
        <AuthHeading title="Password changed" />
        <p className="alert success" role="status">
          Your password has been changed and every session was signed out. Sign in with your new password.
        </p>
        <div className="auth-links">
          <Link to="/login">Go to sign in</Link>
        </div>
      </>
    )
  }

  return (
    <>
      <AuthHeading title="Choose a new password">{PASSWORD_POLICY_TEXT}</AuthHeading>
      <form className="stack-form" onSubmit={submit} noValidate>
        <Field label="New password" required hint={<PasswordChecklist password={password} />}>
          {control => <PasswordInput control={control} value={password} onChange={setPassword} autoComplete="new-password" />}
        </Field>
        <Field label="Confirm new password" required>
          {control => <PasswordInput control={control} value={confirm} onChange={setConfirm} autoComplete="new-password" />}
        </Field>
        <FormError error={formError ?? reset.error} />
        <button className="primary" type="submit" disabled={reset.isPending}>
          {reset.isPending ? 'Saving…' : 'Set new password'}
        </button>
      </form>
      <div className="auth-links">
        <Link to="/forgot-password">Request a new link</Link>
      </div>
    </>
  )
}
