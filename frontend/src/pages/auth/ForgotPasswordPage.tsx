import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { authApi } from '../../api/endpoints'
import { Field } from '../../components/Form'
import { FormError } from '../../components/Feedback'
import { AuthHeading } from './AuthLayout'

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const request = useMutation({ mutationFn: (address: string) => authApi.forgotPassword(address) })

  function submit(event: FormEvent) {
    event.preventDefault()
    request.mutate(email.trim().toLowerCase())
  }

  if (request.isSuccess) {
    return (
      <>
        <AuthHeading title="Check your email" />
        <p className="alert success" role="status">
          If an account exists for {email.trim()}, we have sent a link to reset the password. The link is valid for one
          hour.
        </p>
        <div className="auth-links">
          <Link to="/login">Back to sign in</Link>
        </div>
      </>
    )
  }

  return (
    <>
      <AuthHeading title="Reset your password">Enter the email address you registered with and we will send you a reset link.</AuthHeading>
      <form className="stack-form" onSubmit={submit}>
        <Field label="Email" required>
          {control => (
            <input
              {...control}
              type="email"
              inputMode="email"
              autoComplete="email"
              maxLength={255}
              value={email}
              onChange={event => setEmail(event.target.value)}
            />
          )}
        </Field>
        <FormError error={request.error} />
        <button className="primary" type="submit" disabled={request.isPending}>
          {request.isPending ? 'Sending…' : 'Send reset link'}
        </button>
      </form>
      <div className="auth-links">
        <Link to="/login">Back to sign in</Link>
      </div>
    </>
  )
}
