import { useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { authApi } from '../../api/endpoints'
import { FormError } from '../../components/Feedback'
import { focusFirstInvalid } from '../../components/Form'
import { Button, Field, Input } from '../../design/components'
import { Icon, Mail } from '../../design/icons'
import { AuthHeading } from './AuthLayout'

const EMAIL = /^\S+@\S+\.\S+$/

export function ForgotPasswordPage() {
  const formRef = useRef<HTMLFormElement>(null)
  const [email, setEmail] = useState('')
  const [fieldError, setFieldError] = useState<string>()
  const request = useMutation({ mutationFn: (address: string) => authApi.forgotPassword(address) })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!EMAIL.test(email.trim())) {
      setFieldError('Enter the email address you registered with.')
      return focusFirstInvalid(formRef.current)
    }
    setFieldError(undefined)
    request.mutate(email.trim().toLowerCase())
  }

  if (request.isSuccess) {
    return (
      <div className="auth-state">
        <span className="auth-state-icon" data-tone="neutral">
          <Icon icon={Mail} size={20} />
        </span>
        <AuthHeading title="Check your email">
          If an account exists for <strong>{email.trim()}</strong>, a link to reset the password is on its way. It is valid for one hour.
        </AuthHeading>
        <p className="auth-switch">
          <Link to="/login">Back to sign in</Link>
        </p>
      </div>
    )
  }

  return (
    <>
      <AuthHeading title="Reset your password">Enter the email address you registered with and we will send you a reset link.</AuthHeading>
      <form ref={formRef} onSubmit={submit} noValidate>
        <div className="auth-fields">
          <Field label="Email" error={fieldError}>
            <Input type="email" inputMode="email" autoComplete="email" maxLength={255} controlSize="lg" value={email} autoFocus onChange={e => setEmail(e.target.value)} />
          </Field>
          <FormError error={request.error} />
          <div className="auth-actions">
            <Button type="submit" variant="primary" size="lg" block loading={request.isPending}>
              Send reset link
            </Button>
          </div>
        </div>
      </form>
      <p className="auth-switch">
        Remembered it? <Link to="/login">Back to sign in</Link>
      </p>
    </>
  )
}
