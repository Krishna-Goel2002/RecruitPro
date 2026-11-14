import { useId, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { Field, PasswordChecklist, PasswordInput, fieldErrorMap } from '../../components/Form'
import { FormError } from '../../components/Feedback'
import { useNotify } from '../../components/Notices'
import { PASSWORD_POLICY_TEXT, isPasswordValid } from '../../lib/password'
import { AuthHeading } from './AuthLayout'

interface RegisterForm {
  firstName: string
  lastName: string
  email: string
  password: string
  confirm: string
}

const EMPTY: RegisterForm = { firstName: '', lastName: '', email: '', password: '', confirm: '' }

export function RegisterPage() {
  const { register } = useAuth()
  const navigate = useNavigate()
  const notify = useNotify()
  const checklistId = useId()
  const [form, setForm] = useState<RegisterForm>(EMPTY)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  const set = (key: keyof RegisterForm) => (value: string) => setForm(current => ({ ...current, [key]: value }))

  function validate(): Record<string, string> {
    const next: Record<string, string> = {}
    if (!form.firstName.trim()) next.firstName = 'Enter your first name.'
    if (!form.lastName.trim()) next.lastName = 'Enter your last name.'
    if (!/^\S+@\S+\.\S+$/.test(form.email.trim())) next.email = 'Enter a valid email address.'
    if (!isPasswordValid(form.password)) next.password = PASSWORD_POLICY_TEXT
    if (form.confirm !== form.password) next.confirm = 'The passwords do not match.'
    return next
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    const found = validate()
    setErrors(found)
    setError(null)
    if (Object.keys(found).length > 0) {
      setError('Please correct the highlighted fields.')
      return
    }
    setBusy(true)
    try {
      await register({
        firstName: form.firstName.trim(),
        lastName: form.lastName.trim(),
        email: form.email.trim().toLowerCase(),
        password: form.password,
      })
      notify('success', 'Your account is ready. We sent you a link to verify your email address.')
      navigate('/jobs', { replace: true })
    } catch (caught) {
      if (caught instanceof ApiError && caught.fieldErrors.length > 0) setErrors(fieldErrorMap(caught.fieldErrors))
      setError(caught)
      setBusy(false)
    }
  }

  return (
    <>
      <AuthHeading title="Create your candidate account">
        Apply to roles, track every application and keep your evidence in one place.
      </AuthHeading>
      <form className="stack-form" onSubmit={submit} noValidate>
        <div className="two-columns">
          <Field label="First name" required error={errors.firstName}>
            {control => (
              <input {...control} autoComplete="given-name" maxLength={100} value={form.firstName} onChange={event => set('firstName')(event.target.value)} />
            )}
          </Field>
          <Field label="Last name" required error={errors.lastName}>
            {control => (
              <input {...control} autoComplete="family-name" maxLength={100} value={form.lastName} onChange={event => set('lastName')(event.target.value)} />
            )}
          </Field>
        </div>
        <Field label="Email" required error={errors.email}>
          {control => (
            <input
              {...control}
              type="email"
              inputMode="email"
              autoComplete="email"
              maxLength={255}
              value={form.email}
              onChange={event => set('email')(event.target.value)}
            />
          )}
        </Field>
        <Field label="Password" required error={errors.password} hint={<PasswordChecklist password={form.password} id={checklistId} />}>
          {control => <PasswordInput control={control} value={form.password} onChange={set('password')} autoComplete="new-password" />}
        </Field>
        <Field label="Confirm password" required error={errors.confirm}>
          {control => <PasswordInput control={control} value={form.confirm} onChange={set('confirm')} autoComplete="new-password" />}
        </Field>
        <FormError error={error} />
        <button className="primary" type="submit" disabled={busy}>
          {busy ? 'Creating account…' : 'Create account'}
        </button>
      </form>
      <div className="auth-links">
        <Link to="/login">Already registered? Sign in</Link>
      </div>
    </>
  )
}
