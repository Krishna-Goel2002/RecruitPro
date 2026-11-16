import { useId, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { FormError } from '../../components/Feedback'
import { PasswordChecklist, PasswordInput, fieldErrorMap, focusFirstInvalid } from '../../components/Form'
import { useNotify } from '../../components/Notices'
import { Button, Field, Input } from '../../design/components'
import { ArrowRight, Icon } from '../../design/icons'
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
const EMAIL = /^\S+@\S+\.\S+$/

function validateField(key: keyof RegisterForm, form: RegisterForm): string | undefined {
  switch (key) {
    case 'firstName':
      return form.firstName.trim() ? undefined : 'Enter your first name.'
    case 'lastName':
      return form.lastName.trim() ? undefined : 'Enter your last name.'
    case 'email':
      return EMAIL.test(form.email.trim()) ? undefined : 'Enter a valid email address.'
    case 'password':
      return isPasswordValid(form.password) ? undefined : PASSWORD_POLICY_TEXT
    case 'confirm':
      return form.confirm === form.password ? undefined : 'The passwords do not match.'
  }
}

export function RegisterPage() {
  const { register } = useAuth()
  const navigate = useNavigate()
  const notify = useNotify()
  const checklistId = useId()
  const formRef = useRef<HTMLFormElement>(null)
  const [form, setForm] = useState<RegisterForm>(EMPTY)
  const [errors, setErrors] = useState<Partial<Record<keyof RegisterForm, string>>>({})
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  const set = (key: keyof RegisterForm) => (value: string) => {
    setForm(current => ({ ...current, [key]: value }))
    // Clear a shown error as soon as the field becomes valid.
    if (errors[key] && !validateField(key, { ...form, [key]: value })) setErrors(current => ({ ...current, [key]: undefined }))
  }
  const blur = (key: keyof RegisterForm) => () => {
    if (!form[key]) return
    setErrors(current => ({ ...current, [key]: validateField(key, form) }))
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    const found: typeof errors = {}
    for (const key of Object.keys(EMPTY) as (keyof RegisterForm)[]) {
      const problem = validateField(key, form)
      if (problem) found[key] = problem
    }
    setErrors(found)
    setError(null)
    if (Object.keys(found).length > 0) return focusFirstInvalid(formRef.current)
    setBusy(true)
    try {
      await register({
        firstName: form.firstName.trim(),
        lastName: form.lastName.trim(),
        email: form.email.trim().toLowerCase(),
        password: form.password,
      })
      notify('success', 'Your account is ready', { body: 'We sent a link to confirm your email address.' })
      navigate('/home', { replace: true })
    } catch (caught) {
      if (caught instanceof ApiError && caught.fieldErrors.length > 0) {
        setErrors(fieldErrorMap(caught.fieldErrors))
        focusFirstInvalid(formRef.current)
      } else setError(caught)
      setBusy(false)
    }
  }

  return (
    <>
      <AuthHeading title="Create your account">Apply to roles, follow every application and keep your evidence in one place.</AuthHeading>
      <form ref={formRef} onSubmit={submit} noValidate>
        <div className="auth-fields">
          <div className="auth-row-2">
            <Field label="First name" error={errors.firstName}>
              <Input autoComplete="given-name" maxLength={100} controlSize="lg" value={form.firstName} autoFocus onChange={e => set('firstName')(e.target.value)} onBlur={blur('firstName')} />
            </Field>
            <Field label="Last name" error={errors.lastName}>
              <Input autoComplete="family-name" maxLength={100} controlSize="lg" value={form.lastName} onChange={e => set('lastName')(e.target.value)} onBlur={blur('lastName')} />
            </Field>
          </div>
          <Field label="Email" error={errors.email}>
            <Input type="email" inputMode="email" autoComplete="email" maxLength={255} controlSize="lg" value={form.email} onChange={e => set('email')(e.target.value)} onBlur={blur('email')} />
          </Field>
          <div>
            <Field label="Password" error={errors.password}>
              <PasswordInput controlSize="lg" value={form.password} onChange={set('password')} autoComplete="new-password" aria-describedby={checklistId} onBlur={blur('password')} />
            </Field>
            <PasswordChecklist password={form.password} id={checklistId} />
          </div>
          <Field label="Confirm password" error={errors.confirm}>
            <PasswordInput controlSize="lg" value={form.confirm} onChange={set('confirm')} autoComplete="new-password" onBlur={blur('confirm')} />
          </Field>
          <FormError error={error} />
          <div className="auth-actions">
            <Button type="submit" variant="primary" size="lg" block loading={busy}>
              Create account
            </Button>
            <p className="auth-next">
              <Icon icon={ArrowRight} size={14} />
              <span>Next: confirm your email, then add your resume. We guide you through it.</span>
            </p>
          </div>
        </div>
      </form>
      <p className="auth-switch">
        Already registered? <Link to="/login">Sign in</Link>
      </p>
    </>
  )
}
